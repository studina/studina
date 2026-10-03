# Studina: tu copiloto del aprendizaje

**Integrantes:**
- Belén Cerrotti
- Celeste Gómez
- Mariana Juarez Goldemberg
- Miranda Marenzi

---

## Requisitos previos

- Docker y Docker Compose
- Node.js 18+
- Openjdk 21 ([instrucciones de instalacion](#instalar-openjdk))
- Gcloud ([guía de gcloud](#gcloud-y-model-armor))
- Ollama (opcional, sólo si se quieren probar modelos locales; ver la
  [guía completa](#probar-ollama-localmente-en-linux-con-docker))

---

## Variables de entorno

Crear un archivo `.env` dentro de `/dinaapi` a partir del arhivo de ejemplo (`.env.example`) y establecer las variables con las configuraciones necesarias.

### Proveedor del modelo de chat

El backend permite elegir el proveedor de chat sin modificar el código:

```env
AI_CHAT_PROVIDER=google-genai
AI_CHAT_MODEL=gemini-2.5-flash
AI_GENERATION_MODEL=gemini-2.5-flash
AI_CLASSIFICATION_MODEL=gemini-2.5-flash
AI_SUMMARY_MODEL=gemini-2.5-flash
AI_CHAT_TEMPERATURE=0.2
```

`AI_CHAT_MODEL` es el valor general. Los otros tres valores permiten usar un
modelo distinto para generar flashcards/cuestionarios, clasificar documentos y
resumir conversaciones. Si no se configuran, esas tareas usan automáticamente
`AI_CHAT_MODEL`.

Los proveedores disponibles son:

- `google-genai`: usa Gemini y es el valor por defecto.
- `ollama`: usa una instancia local de Ollama.
- `openai`: usa OpenAI o un endpoint compatible configurado por el servidor.

Para utilizar Ollama hay que instalarlo en la computadora o servidor que ejecuta
Studina y descargar por lo menos un modelo. Cuando el proyecto se levanta con el
`compose.yaml` de este repositorio, el backend usa automáticamente
`http://host.docker.internal:11434`; no hace falta que cada desarrollador cambie
su archivo `.env`. La instalación completa, las verificaciones y los errores más
comunes están explicados en
[Probar Ollama localmente en Linux con Docker](#probar-ollama-localmente-en-linux-con-docker).

Cuando la API se ejecuta directamente en el host, fuera de Docker,
`OLLAMA_BASE_URL` debe ser `http://localhost:11434`. Los embeddings continúan
usando Gemini aunque el chat use Ollama, para no invalidar los documentos ya
indexados en pgvector.

### Proveedor y credenciales por usuario

Cada usuario autenticado puede entrar en **Mi perfil**, elegir Gemini, Ollama u
OpenAI y configurar modelos distintos para chat, generación, clasificación y
resúmenes. Si no guarda una configuración personal, se utiliza la configuración
general definida por las variables anteriores.

Para permitir que los usuarios guarden sus propias API keys, generar una clave
de cifrado del servidor:

```bash
openssl rand -base64 32
```

Copiar el resultado en `environment/dinaapi.env`:

```env
AI_CREDENTIALS_ENCRYPTION_KEY=resultado_generado
```

La API key del usuario se manda una sola vez al backend, se cifra con AES-256-GCM
antes de almacenarse en Directus y nunca se devuelve al navegador. Ollama no
solicita una API key. La URL de Ollama y `OPENAI_BASE_URL` sólo pueden definirlas
los responsables del servidor; no se aceptan URLs arbitrarias enviadas desde el
frontend. En un despliegue real, el frontend y la API deben publicarse detrás de
HTTPS para proteger la credencial durante el envío.

---

## Cómo funciona la inteligencia artificial en Studina

### La versión corta

Studina no contiene un modelo de inteligencia artificial propio. La aplicación
prepara una consulta y se la entrega a un **proveedor**. Ese proveedor ejecuta un
**modelo** y devuelve una respuesta.

```text
Usuario
  ↓
Frontend de Studina
  ↓
Backend dina-api
  ↓
Spring AI elige el conector correcto
  ├── Google Gemini (en Internet)
  ├── OpenAI o API compatible (en Internet)
  └── Ollama (en nuestra computadora o servidor)
  ↓
Respuesta mostrada por Dina
```

En palabras simples:

- **Proveedor:** la empresa o programa que permite ejecutar modelos. Por
  ejemplo, Google, OpenAI u Ollama.
- **Modelo:** el “cerebro” concreto que procesa el pedido. Por ejemplo,
  `gemini-2.5-flash`, `gpt-4.1-mini` o `llama3.2`.
- **API key:** una credencial secreta que autoriza a usar un proveedor remoto.
  No es lo mismo que una suscripción de ChatGPT. Ollama local no necesita una.
- **Prompt:** las instrucciones y el texto que Studina le entrega al modelo.
- **Token:** un pedacito de texto. Los modelos leen y producen tokens; los
  proveedores remotos normalmente cobran o limitan el uso según esa cantidad.
- **Contexto:** la cantidad de información que el modelo puede considerar en
  una consulta. No equivale a memoria permanente.

Un modelo de lenguaje genera la respuesta prediciendo qué token debería venir a
continuación. No consulta automáticamente una fuente confiable y puede
equivocarse o inventar datos. Studina reduce ese problema agregando instrucciones
y, cuando corresponde, fragmentos de los documentos del usuario.

### Qué es Spring AI

[Spring AI](https://docs.spring.io/spring-ai/reference/) es una biblioteca para
aplicaciones Java basadas en Spring. **No es un modelo y no genera respuestas por
sí misma.** Funciona como un adaptador común entre Studina y los distintos
proveedores.

Sin esa capa, el backend tendría código diferente por todos lados para llamar a
Gemini, OpenAI y Ollama. Con Spring AI, Studina arma el pedido mediante una
interfaz común (`ChatClient`) y la implementación correspondiente lo traduce al
formato que entiende cada proveedor.

En este proyecto la abstracción propia de LLM agrega otra capa pequeña sobre
Spring AI:

1. Recibe la tarea que quiere realizar Studina.
2. Busca la configuración personal del usuario; si no existe, usa la general del
   servidor.
3. Elige proveedor, modelo y credencial.
4. Crea el cliente de Spring AI correspondiente.
5. Envía el prompt y devuelve tanto el texto como el proveedor y modelo realmente
   utilizados.

Por eso el chat puede mostrar una insignia como **“Estás usando Ollama ·
llama3.2”** y cada respuesta conserva esa información. No se confía únicamente
en lo que el modelo diga sobre sí mismo.

### Por qué hay cuatro selectores de modelos

Desde **Mi perfil** se puede elegir un modelo diferente para cada tipo de trabajo:

| Selector | Para qué se usa |
| --- | --- |
| Modelo para conversar | Respuestas normales del chat de Dina. |
| Flashcards y quizzes | Generación de tarjetas y cuestionarios a partir de los documentos. |
| Clasificar documentos | Decidir cómo categorizar el contenido cargado. |
| Resumir conversaciones | Compactar conversaciones largas para conservar contexto útil. |

Separarlos permite, por ejemplo, usar un modelo rápido y económico para
clasificar, y reservar uno más potente para generar contenido. Si se selecciona
**Usar configuración general**, vuelven a aplicarse los valores definidos por el
servidor en `environment/dinaapi.env`.

El catálogo del frontend contiene modelos conocidos y compatibles con cada
proveedor. En Ollama, además de seleccionarlo en la pantalla, el modelo debe
estar descargado en la máquina que ejecuta Ollama. El texto elegido debe coincidir
con su nombre o etiqueta, por ejemplo `llama3.2`.

### Documentos, embeddings y RAG

Un chat común no puede “ver” todos los documentos guardados. Para encontrar qué
partes son relevantes, Studina usa este proceso:

```text
Subir documento
  → extraer el texto
  → dividirlo en fragmentos
  → convertir cada fragmento en un embedding
  → guardar los vectores en PostgreSQL + pgvector

Hacer una pregunta
  → convertir la pregunta en un embedding
  → buscar los fragmentos más parecidos
  → adjuntarlos al prompt
  → pedir la respuesta al modelo de chat seleccionado
```

Un **embedding** es una lista de números que representa el significado aproximado
de un texto. **pgvector** permite comparar esas listas para encontrar contenido
parecido. Agregar al prompt los fragmentos encontrados se conoce como **RAG**
(*Retrieval-Augmented Generation* o generación aumentada con recuperación).

Importante: actualmente los embeddings de Studina se generan con Gemini, incluso
si el usuario elige Ollama para conversar. Esto mantiene compatible el índice ya
existente, pero significa que el flujo de documentos todavía no es 100 % local y
que `GEMINI_API_KEY` sigue siendo necesaria. El chat sin documentos sí puede
responder mediante Ollama.

### Diferencias prácticas entre los proveedores

| Proveedor | Dónde se ejecuta el modelo | ¿API key personal? | Consideraciones |
| --- | --- | --- | --- |
| Google Gemini | Servidores de Google | Sí | Puede tener cuota gratuita según la cuenta y la región. |
| OpenAI / compatible | Servidor remoto configurado | Sí | La API se factura aparte de ChatGPT; una cuenta con saldo `$0` no puede responder. |
| Ollama | PC o servidor administrado por Studina | No | Consume RAM, CPU/GPU y disco de esa máquina; el modelo debe estar descargado. |

---

## Probar Ollama localmente en Linux con Docker

Esta guía describe el caso usado por el equipo: **Ollama corre en Linux y
Studina corre con Docker Compose**.

La conexión queda así:

```text
Navegador → frontend → dina-api (contenedor Docker)
                             ↓
               host.docker.internal:11434
                             ↓
                    Ollama en la PC
                             ↓
                       llama3.2
```

### 1. Instalar Ollama

La instalación oficial para Linux es:

```bash
curl -fsSL https://ollama.com/install.sh | sh
```

Comprobar que el comando quedó instalado:

```bash
ollama --version
```

En distribuciones que usan `systemd`, comprobar también el servicio:

```bash
sudo systemctl enable --now ollama
sudo systemctl status ollama --no-pager
```

El estado esperado contiene `Active: active (running)`. Para salir de la vista
interactiva de `systemctl status`, presionar `q`.

> La página oficial también ofrece instaladores para macOS y Windows. Los pasos
> de red de esta sección están pensados específicamente para Linux + Docker.

### 2. Descargar y probar el modelo

Descargar Llama 3.2 una sola vez:

```bash
ollama pull llama3.2
```

Ver los modelos descargados:

```bash
ollama list
```

Probarlo sin Studina:

```bash
ollama run llama3.2
```

Escribir, por ejemplo, `Respondeme en español: ¿qué es una API?`. Si contesta,
el modelo y Ollama funcionan. Para cerrar el chat usar `/bye` o `Ctrl+D`.

Los modelos ocupan varios gigabytes. La primera respuesta puede tardar más porque
Ollama tiene que cargar los pesos del modelo en RAM y, si está disponible, VRAM.

### 3. Permitir que Docker llegue a Ollama

Este es el paso fácil de pasar por alto. Ollama escucha de forma predeterminada
solamente en `127.0.0.1:11434`. Además, dentro del contenedor de `dina-api`,
`localhost` significa “este contenedor”, no la computadora anfitriona. Por eso un
error como este no significa que el modelo esté roto:

```text
Connection refused: localhost/127.0.0.1:11434
```

Crear una configuración adicional para el servicio de Ollama:

```bash
sudo mkdir -p /etc/systemd/system/ollama.service.d
printf '[Service]\nEnvironment="OLLAMA_HOST=0.0.0.0:11434"\n' \
  | sudo tee /etc/systemd/system/ollama.service.d/override.conf
```

Aplicarla y reiniciar Ollama:

```bash
sudo systemctl daemon-reload
sudo systemctl restart ollama
```

Verificar que la variable fue tomada:

```bash
systemctl show ollama --property=Environment --no-pager
```

La salida debe incluir:

```text
OLLAMA_HOST=0.0.0.0:11434
```

También se puede confirmar que el puerto está escuchando:

```bash
ss -ltn 'sport = :11434'
```

> **Seguridad:** `0.0.0.0` hace que Ollama escuche en las interfaces de red de la
> máquina. Usarlo sólo en una red local confiable o con firewall. No publicar el
> puerto `11434` directamente en Internet. En producción conviene limitar por red
> qué backend puede acceder al servidor de Ollama.

Si `sudo systemctl edit ollama` muestra
`temporary file is empty` o resulta incómodo guardar desde el editor, el comando
con `tee` de arriba crea el archivo directamente y evita ese problema.

### 4. Verificar la API desde la computadora

Ollama expone una API HTTP local. Este endpoint lista los modelos disponibles:

```bash
curl --fail http://localhost:11434/api/tags
```

La respuesta debe ser JSON y contener un nombre como `llama3.2:latest`. Si este
comando falla, todavía hay que resolver Ollama antes de revisar Studina.

### 5. Levantar Studina y verificar la conexión desde Docker

Desde la raíz del repositorio:

```bash
make dev
```

Si Studina ya estaba levantada antes de configurar Ollama, recrear el backend:

```bash
docker compose up -d --force-recreate dina-api
```

`docker compose restart` no alcanza cuando cambió una variable de entorno o la
configuración de Compose: reinicia el mismo contenedor con su configuración
anterior. `--force-recreate` crea uno nuevo con los valores actuales.

Ahora probar desde el mismo contenedor que hará la consulta:

```bash
docker compose exec -T dina-api sh -lc \
  'curl --fail --silent http://host.docker.internal:11434/api/tags'
```

Si aparece `llama3.2:latest`, toda la ruta de red está funcionando. El
`compose.yaml` ya configura automáticamente para `dina-api`:

```text
OLLAMA_BASE_URL=http://host.docker.internal:11434
```

Por eso, para el desarrollo normal con Docker, no hace falta modificar
`environment/dinaapi.env`. Si se ejecuta el backend directamente con
`./gradlew bootRun`, fuera de Docker, usar
`OLLAMA_BASE_URL=http://localhost:11434`.

### 6. Elegir Ollama desde el frontend

1. Abrir [http://localhost:5173](http://localhost:5173) e iniciar sesión.
2. Entrar en el menú del usuario y abrir **Mi perfil**.
3. En **Proveedor de inteligencia artificial**, elegir **Ollama local**.
4. Elegir **Llama 3.2** en “Modelo para conversar” y en los selectores de las
   otras tareas que se quieran probar.
5. Presionar **Guardar configuración**.
6. Abrir Dina, empezar un chat sin documentos y enviar `Hola, respondeme en
   español`.
7. Comprobar que la cabecera y la respuesta muestran **Ollama · llama3.2**.

No hay que ingresar una API key para Ollama. Si la pantalla parece conservar una
versión vieja, hacer una recarga completa con `Ctrl+Shift+R`.

### 7. Ver si el modelo está trabajando

En otra terminal:

```bash
watch -n 1 ollama ps
```

Una salida como esta confirma que el modelo está cargado:

```text
NAME               SIZE      PROCESSOR          CONTEXT    UNTIL
llama3.2:latest    2.9 GB    14%/86% CPU/GPU    4096       4 minutes from now
```

- `PROCESSOR` muestra si trabaja con CPU, GPU o ambas.
- `CONTEXT` indica el tamaño de contexto cargado.
- `UNTIL` indica cuándo se descargará de memoria si no recibe nuevas consultas.

Que `ollama ps` esté vacío no es necesariamente un error: puede significar que
ningún modelo está atendiendo una consulta o que ya se descargó de memoria por
inactividad. `ollama list`, en cambio, muestra los modelos instalados en disco.

Para mirar el backend mientras se hace la prueba:

```bash
docker compose logs -f dina-api
```

Salir del seguimiento de logs con `Ctrl+C`; eso no apaga los contenedores.

### 8. Problemas frecuentes

| Síntoma | Causa probable | Solución |
| --- | --- | --- |
| `Connection refused: localhost/127.0.0.1:11434` en `dina-api` | El contenedor intenta hablar consigo mismo o conserva una configuración vieja. | Ejecutar `docker compose up -d --force-recreate dina-api` y revisar que use `host.docker.internal`. |
| `curl localhost:11434/api/tags` funciona, pero el `curl` dentro del contenedor falla | Ollama sólo escucha en `127.0.0.1` o el override no fue aplicado. | Revisar `systemctl show ollama --property=Environment --no-pager`, aplicar `OLLAMA_HOST=0.0.0.0:11434` y reiniciar el servicio. |
| `sudo systemctl edit ollama` termina con “temporary file is empty” | El editor se cerró sin guardar contenido válido. | Crear el override con el comando `printf ... | sudo tee ...` de esta guía. |
| `model ... not found` | El nombre seleccionado en Studina no está descargado. | Ejecutar `ollama pull llama3.2`, verificar con `ollama list` y elegir exactamente ese modelo. |
| La primera respuesta tarda mucho | El modelo se está cargando en memoria o la PC trabaja principalmente con CPU. | Esperar la primera carga y revisar `ollama ps`. Modelos más pequeños consumen menos recursos. |
| `ollama ps` no muestra nada | No hay un modelo cargado en ese momento. | Enviar una consulta desde Studina o ejecutar `ollama run llama3.2`. |
| El perfil dice Ollama pero el chat falla | La elección se guardó, pero el backend no alcanza el servidor local. | Ejecutar las verificaciones de los pasos 4 y 5 y mirar `docker compose logs --since=2m dina-api`. |
| El chat funciona, pero una operación con documentos falla | Los embeddings aún dependen de Gemini. | Confirmar que `GEMINI_API_KEY` sea válida y que la cuota de Gemini esté disponible. |

### 9. Detener o volver a iniciar Ollama

```bash
sudo systemctl stop ollama
sudo systemctl start ollama
sudo systemctl restart ollama
```

Detener los contenedores de Studina no detiene Ollama porque es un servicio de la
computadora anfitriona:

```bash
make stop
```

### 10. Qué cambia cuando Studina está desplegada

En una instalación real **el usuario final no debería editar variables de
entorno, instalar Ollama ni tocar `systemd`**. Esas tareas corresponden a quien
administra la infraestructura:

1. Instala Ollama en un servidor con recursos suficientes.
2. Descarga los modelos habilitados por Studina.
3. Configura una conexión de red privada desde `dina-api` hacia Ollama.
4. Define `OLLAMA_BASE_URL` una sola vez en el entorno del backend.
5. Mantiene el catálogo del frontend alineado con los modelos realmente
   instalados.

Después de eso, el usuario sólo entra en **Mi perfil**, selecciona Ollama y el
modelo permitido, y guarda. La computadora del usuario no ejecuta el modelo; lo
hace el servidor. Esto también implica dimensionar CPU/GPU, RAM y concurrencia:
varias consultas simultáneas compiten por los recursos del servidor.

### Referencias oficiales

- [Instalación de Ollama en Linux](https://docs.ollama.com/linux)
- [Inicio rápido de Ollama](https://docs.ollama.com/quickstart)
- [Preguntas frecuentes y configuración de red de Ollama](https://docs.ollama.com/faq)
- [API de Ollama para listar modelos](https://docs.ollama.com/api/tags)
- [Referencia de Spring AI](https://docs.spring.io/spring-ai/reference/)
- [Chat Model API de Spring AI](https://docs.spring.io/spring-ai/reference/api/chatmodel.html)
- [Integración de Spring AI con Ollama](https://docs.spring.io/spring-ai/reference/api/chat/ollama-chat.html)

---

## Instalar OpenJDK
Se puede instalar usando apt:
```bash
sudo apt update
sudo apt install openjdk-21-jdk
```

---

## Gcloud y Model Armor
### Instalación de gcloud
Para correr model armor se necesita instalar el sdk de [gcloud](https://docs.cloud.google.com/sdk/docs/install-sdk#deb).

### Autenticación
Luego de instalar gcloud, autenticarse mediante la documentación proporcionada [aquí](https://docs.cloud.google.com/docs/authentication/set-up-adc-local-dev-environment) para poder utilizar model armor.

### Configuración
Hay que tener un proyecto creado en gcloud, para el cual se tiene que habilitar model armor y crear un template.

Luego se toma el nombre del proyecto y se agrega al archivo `.env`, con la forma mostrada mediante `.env.example`

---

## Inicio rápido

Con `make` instalado, desde la raíz del proyecto:

```bash
make dev    # levanta todo
make stop   # baja los servicios Docker
```

> El backend queda corriendo en background. Al hacer Ctrl+C se detiene el frontend; para bajar Docker usar `make stop`.

Una vez levantado, el frontend estará disponible en [http://localhost:5173](http://localhost:5173).

---

## Levantar los servicios (manual)

### 1. Backend — dinaapi

Desde `/dinaapi`, levantar la base de datos vectorial:

```bash
docker compose up -d
```

En otra terminal, dar permisos y arrancar la API:

```bash
chmod +x ./gradlew
./gradlew bootRun
```

### 2. CMS — Directus

Desde `/directus`:

```bash
docker compose up -d
```

Disponible en [http://localhost:8055](http://localhost:8055).

### 3. Frontend

Desde `/frontend`:

```bash
npm install
npm run dev
```

Disponible en [http://localhost:5173](http://localhost:5173).
