# Dina API
## Prerequisitos
- Openjdk 21 ([instrucciones de instalacion](#instalar-openjdk))
- Gcloud ([guía de gcloud](#gcloud-y-model-armor))

### Instalar OpenJDK
Se puede instalar usando apt:
```bash
sudo apt update
sudo apt install openjdk-21-jdk
```

### Gcloud y Model Armor
#### Instalación de gcloud
Para correr model armor se necesita instalar el sdk de [gcloud](https://docs.cloud.google.com/sdk/docs/install-sdk#deb).

#### Autenticación
Luego de instalar gcloud, autenticarse mediante la documentación proporcionada [aquí](https://docs.cloud.google.com/docs/authentication/set-up-adc-local-dev-environment) para poder utilizar model armor.

## Configuración
Hay que tener un proyecto creado en gcloud, para el cual se tiene que habilitar model armor y crear un template.

Luego se toma el nombre del proyecto y se agrega al archivo `.env`, con la forma mostrada mediante `.env.example`

## Levantar el proyecto
Primero se deben levantar los servicios correspondientes a la base de datos y directus.

Luego, asegurarse de tener permisos de ejecución para el archivo `gradlew`, para poder compilar y ejecutar el proyecto:
```bash
chmod +x ./gradlew
```

Luego, ejecutar el comando:
```bash
./gradlew bootRun
```

Esto levanta el backend para recibir peticiones con la configuaración provista en el archivo `.env`.
