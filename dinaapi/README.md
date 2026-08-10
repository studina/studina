# Dina API
Como se levanta el proyecto? Necesitas tener openjdk 21.

Se puede instalar usando apt:
```bash
sudo apt update
sudo apt install openjdk-21-jdk
```

luego dar permisos al archivo de gradlew:
```bash
chmod +x ./gradlew
```

Ejecutar:
```bash
./gradlew bootRun
```

## Gcloud y Model Armor
### Instalación de gcloud
https://docs.cloud.google.com/sdk/docs/install-sdk#deb

### Autenticacion de gcloud para usar model armor en el entorno local
https://docs.cloud.google.com/docs/authentication/set-up-adc-local-dev-environment

### Configuración necesaria
Hay que tener un proyecto creado en gcloud, para el cual se tiene que habilitar model armor y crear un template.

Luego se toma el nombre del proyecto y se agrega al .env

```bash
RAG_DB_URL=jdbc:postgresql://localhost:5433/studina_rag
RAG_DB_USERNAME=studina
RAG_DB_PASSWORD=studina123
MODEL_ARMOR_URL=https://modelarmor.<region>.rep.googleapis.com
MODEL_ARMOR_API_KEY=api_key
MODEL_ARMOR_PROJECT_ID=nombre_del_proyecto_gcloud
MODEL_ARMOR_LOCATION=region_proyecto_gcloud
MODEL_ARMOR_TEMPLATE_ID=id_template_model_armor
```