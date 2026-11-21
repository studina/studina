# Deploy de Studina en AWS

El stack ejecuta frontend con Caddy, API Java, Directus y dos bases PostgreSQL
(una con pgvector) en una VM. Usa el código actual de la aplicación y su migrador
de esquema, incluidos los planes de estudio y las preferencias de IA.

## Preparar la VM

Crear una EC2 con Ubuntu 24.04 amd64, almacenamiento persistente y salida a
Internet para descargar dependencias y usar Gemini/OpenAI. Configurar un dominio
que resuelva a su IP pública. Permitir TCP 80/443 para la web y limitar SSH a las
direcciones que necesiten administrar el servidor. Los puertos de API, Directus
y las bases no se publican.

Clonar este repositorio y seleccionar el commit que se va a desplegar. Desde su
raíz, instalar Docker y generar la configuración:

```bash
sudo ./scripts/aws/bootstrap-ubuntu.sh
./scripts/aws/setup-env.sh TU_DOMINIO TU_EMAIL
```

El segundo comando solicita la clave Gemini sin mostrarla, genera las contraseñas
y una clave de cifrado de 32 bytes, y crea `deploy/aws/.env` con permisos `0600`.
No sobreescribe archivos existentes. La plantilla es `.env.example`; el archivo
real y los backups quedan fuera de Git. Los scripts leen ese archivo como datos,
sin ejecutarlo como código de shell.

Conservar una copia privada de `.env` fuera de la VM. La clave de cifrado permite
leer las credenciales personales guardadas. Cambiar las variables de bootstrap
de PostgreSQL o Directus no cambia las contraseñas de una instalación existente.

## Modelos y HTTPS

`AI_CHAT_MODEL` configura el modelo general. Se puede agregar
`GEMINI_STUDY_PLAN_MODEL` a `.env` para usar otro modelo en el planificador; por
defecto hereda el general. `AI_GENERATION_MODEL`, `AI_CLASSIFICATION_MODEL` y
`AI_SUMMARY_MODEL` permiten separar las demás tareas. Los modelos elegidos deben
estar disponibles para la cuenta Gemini. Los embeddings y el planificador usan
la clave del servidor; OpenAI usa la clave personal del usuario.

`STUDINA_DOMAIN` contiene sólo el nombre DNS, sin protocolo ni ruta. Caddy obtiene
y renueva certificados automáticamente y necesita acceso desde Internet por
80/443. Opcionalmente, `STUDINA_PUBLIC_IP` habilita también HTTPS para una IPv4
pública fija. Los certificados usan el perfil ACME `shortlived`: comprobar HTTPS
con margen si la VM estuvo apagada varios días. Conservar los volúmenes de Caddy.

## Desplegar y actualizar

```bash
sudo ./scripts/aws/deploy.sh
sudo ./scripts/aws/compose.sh ps --all
sudo ./scripts/aws/compose.sh logs --tail=100 dina-api directus frontend
```

El despliegue valida la configuración, descarga imágenes, compila backend y
frontend en secuencia y espera a las bases y Directus. Luego detiene la API,
ejecuta el migrador aditivo y sólo inicia API y frontend si la migración terminó
correctamente. El contenedor `directus-schema` terminado con código 0 es esperado.
Hay una interrupción durante la migración y la recreación de los servicios.

Antes de actualizar, guardar un backup y obtener el nuevo commit. Ejecutar otra
vez `deploy.sh`. Si falla, revisar el estado y los logs antes de reintentar.

El wrapper fija el proyecto `studina-aws`, el Compose de producción y su `.env`.
Las variables y los perfiles de una sesión de desarrollo no activan servicios
adicionales. Para detener los servicios sin eliminar sus datos:

```bash
sudo make aws-stop
```

No usar `make clean` ni `down --volumes` en producción. Los logs se consultan
con Docker; esta configuración no los envía a CloudWatch.

## Comprobar la aplicación

Desde otra máquina, abrir `https://TU_DOMINIO` y comprobar registro, login,
carga y descarga de documentos, generación de recursos y guardado de planes de
estudio. Probar también una clave personal si se va a usar OpenAI. Revisar la
persistencia después de reiniciar. Los healthchecks no verifican por sí solos
DNS, certificados ni la disponibilidad de los proveedores de IA.

## Ollama opcional

El despliegue normal habilita Gemini/OpenAI. Para preparar Ollama y descargar
`llama3.2:3b` y `gemma3:1b`:

```bash
sudo make aws-ollama-prepare
sudo make aws-ollama-status
```

La preparación requiere al menos 14 GiB libres en el disco de Docker y sólo
inicia Ollama. El contenedor tiene un límite de 4 GiB de RAM, no publica puertos
y mantiene los modelos en un volumen. Para habilitarlo en el perfil y ampliar
los tiempos del proxy, aplicar explícitamente el archivo adicional:

```bash
sudo ./scripts/aws/compose.sh --ollama up --detach --no-deps --wait dina-api frontend
```

Gemini sigue como proveedor general. Para conservar Ollama al actualizar, usar
`sudo ./scripts/aws/deploy.sh --ollama`. Un despliegue sin ese flag vuelve a la
configuración Gemini/OpenAI.

## Model Armor opcional

La API puede consultar Google Model Armor desde AWS. El despliegue normal lo
mantiene deshabilitado y no requiere credenciales Google Cloud. Para activarlo,
reutilizar el proyecto, el template y la identidad autorizada de la integración
existente. Este overlay sólo configura el despliegue y conserva el código de
Model Armor de la aplicación. El template debe estar en una
[ubicación compatible](https://docs.cloud.google.com/model-armor/locations).
La identidad de la aplicación necesita el rol
[`roles/modelarmor.user`](https://docs.cloud.google.com/model-armor/access-control/service-accounts)
en el proyecto del template; la clave de Gemini no sustituye esas credenciales.

Preparar un archivo Application Default Credentials (ADC). Para EC2 se recomienda
[Workload Identity Federation con AWS](https://docs.cloud.google.com/iam/docs/workload-identity-federation-with-other-clouds),
que usa la identidad de la VM sin guardar una clave privada Google. La
configuración debe usar `credential_source.environment_id=aws1`; comprobar que el
contenedor puede acceder a los metadatos de EC2, incluido IMDSv2 si está activo.
El overlay también acepta un JSON de cuenta de servicio si ya se dispone de uno.
Guardar el archivo fuera del repositorio y darle permisos `0600`.

Completar en `deploy/aws/.env`:

```env
MODEL_ARMOR_PROJECT_ID=tu-proyecto-google
MODEL_ARMOR_LOCATION=us-central1
MODEL_ARMOR_TEMPLATE_ID=tu-template
MODEL_ARMOR_CREDENTIALS_FILE=/etc/studina/model-armor-adc.json
```

No agregar el JSON a Git ni pegar su contenido en `.env`. El archivo se monta
como sólo lectura únicamente en `dina-api`; no se incorpora a la imagen. La
región debe coincidir con la del template: la aplicación usa el endpoint regional
de Model Armor y no admite `global`.

```bash
# Valida configuración/ADC y actualiza la aplicación con Model Armor.
sudo ./scripts/aws/deploy.sh --model-armor

# También se puede conservar Ollama; el orden de los flags es indistinto.
sudo ./scripts/aws/deploy.sh --ollama --model-armor
sudo ./scripts/aws/compose.sh --model-armor --ollama ps --all
```

La validación local verifica formato, presencia y permisos del archivo; no
comprueba credenciales contra Google ni la existencia o política del template.
Antes de dar la activación por terminada, probar una consulta académica válida y
una entrada que el template deba bloquear, y revisar los logs de `dina-api`.
Mantener `--model-armor` en futuras actualizaciones. Para desactivarlo, desplegar
sin ese flag; no hace falta borrar el archivo de credenciales.

## Backups

```bash
sudo ./scripts/aws/backup.sh
```

El backup pausa frontend, API y Directus durante la copia y luego los reanuda.
Guarda ambas bases, archivos de Directus, datos de Caddy, configuración y commit
en `backups/FECHA_UTC/`, con permisos restringidos y sumas de integridad. Una
carpeta `.partial` indica una copia incompleta. El backup contiene secretos:
copiarlo a almacenamiento privado fuera de la VM.

La restauración es manual y debe ensayarse en una VM separada: recuperar el mismo
commit y `.env`, verificar `SHA256SUMS`, iniciar bases vacías, restaurar los dumps
con `pg_restore` y recuperar los archivos de Directus y Caddy en sus volúmenes.
Ejecutar después `deploy.sh` y comprobar documentos y credenciales personales.
El script de backup no restaura datos ni crea snapshots o copias en S3.
Incluye los overlays de Ollama y Model Armor y registra los archivos Compose del
contenedor en uso. El ADC externo de Model Armor no se copia: conservar su
configuración de federación o su copia privada por separado, reponerla en la ruta
de `.env` y restaurar usando los mismos flags de despliegue.
