import os
import uuid
from datetime import datetime

import boto3
from botocore.client import Config
from botocore.exceptions import ClientError


MINIO_ENDPOINT = "http://localhost:9000"
MINIO_ACCESS_KEY = "studina_admin"
MINIO_SECRET_KEY = "studina_admin_123456"

BUCKET_NAME = "studina-documents"


def create_s3_client():
    return boto3.client(
        "s3",
        endpoint_url=MINIO_ENDPOINT,
        aws_access_key_id=MINIO_ACCESS_KEY,
        aws_secret_access_key=MINIO_SECRET_KEY,
        config=Config(signature_version="s3v4"),
        region_name="us-east-1",
    )


def ensure_bucket_exists(s3, bucket_name):
    existing_buckets = [b["Name"] for b in s3.list_buckets()["Buckets"]]

    if bucket_name not in existing_buckets:
        s3.create_bucket(Bucket=bucket_name)
        print(f"Bucket creado: {bucket_name}")
    else:
        print(f"Bucket ya existe: {bucket_name}")


def create_test_file(filename):
    content = """
Este sería un apunte subido por un usuario de Studina.

Tema: Sistemas Distribuidos
Unidad: Raft
Contenido: elección de líder, replicación de logs y consenso distribuido.
"""

    with open(filename, "w", encoding="utf-8") as f:
        f.write(content)

    print(f"Archivo local creado: {filename}")


def upload_file_with_metadata(s3, bucket_name, local_filename, object_key):
    s3.upload_file(
        local_filename,
        bucket_name,
        object_key,
        ExtraArgs={
            "ContentType": "text/plain",
            "Metadata": {
                "user_id": "1",
                "subject": "sistemas-distribuidos",
                "document_type": "apunte",
                "uploaded_at": datetime.utcnow().isoformat(),
            },
        },
    )

    print(f"Archivo subido a MinIO: s3://{bucket_name}/{object_key}")


def list_objects(s3, bucket_name, prefix=""):
    response = s3.list_objects_v2(
        Bucket=bucket_name,
        Prefix=prefix,
    )

    print(f"\nObjetos en bucket '{bucket_name}' con prefix '{prefix}':")

    objects = response.get("Contents", [])

    if not objects:
        print("No hay objetos.")
        return

    for obj in objects:
        print(f"- {obj['Key']} ({obj['Size']} bytes)")


def get_object_metadata(s3, bucket_name, object_key):
    response = s3.head_object(
        Bucket=bucket_name,
        Key=object_key,
    )

    print("\nMetadata del archivo:")
    print("Content-Type:", response.get("ContentType"))
    print("Tamaño:", response.get("ContentLength"), "bytes")
    print("Última modificación:", response.get("LastModified"))

    print("Metadata custom:")
    for key, value in response.get("Metadata", {}).items():
        print(f"- {key}: {value}")


def download_file(s3, bucket_name, object_key, output_filename):
    s3.download_file(bucket_name, object_key, output_filename)
    print(f"\nArchivo descargado como: {output_filename}")

    with open(output_filename, "r", encoding="utf-8") as f:
        print("\nContenido descargado:")
        print(f.read())


def generate_presigned_download_url(s3, bucket_name, object_key):
    url = s3.generate_presigned_url(
        "get_object",
        Params={
            "Bucket": bucket_name,
            "Key": object_key,
        },
        ExpiresIn=3600,
    )

    print("\nURL temporal de descarga, válida por 1 hora:")
    print(url)


def object_exists(s3, bucket_name, object_key):
    try:
        s3.head_object(Bucket=bucket_name, Key=object_key)
        return True
    except ClientError as e:
        status_code = e.response["ResponseMetadata"]["HTTPStatusCode"]

        if status_code == 404:
            return False

        raise


def test_missing_file(s3, bucket_name):
    missing_key = "users/1/documents/archivo-inexistente.txt"

    print("\nProbando archivo inexistente...")

    if object_exists(s3, bucket_name, missing_key):
        print("El archivo existe.")
    else:
        print("El archivo no existe, como se esperaba.")


def delete_object(s3, bucket_name, object_key):
    s3.delete_object(
        Bucket=bucket_name,
        Key=object_key,
    )

    print(f"\nArchivo eliminado: s3://{bucket_name}/{object_key}")


def main():
    s3 = create_s3_client()

    ensure_bucket_exists(s3, BUCKET_NAME)

    local_filename = "apunte.txt"
    downloaded_filename = "apunte-descargado.txt"

    document_id = str(uuid.uuid4())

    object_key = f"users/1/subjects/sistemas-distribuidos/documents/{document_id}.txt"

    create_test_file(local_filename)

    upload_file_with_metadata(
        s3=s3,
        bucket_name=BUCKET_NAME,
        local_filename=local_filename,
        object_key=object_key,
    )

    list_objects(
        s3=s3,
        bucket_name=BUCKET_NAME,
        prefix="users/1/",
    )

    if object_exists(s3, BUCKET_NAME, object_key):
        print("\nEl archivo existe en MinIO.")
    else:
        print("\nEl archivo no existe en MinIO.")

    get_object_metadata(
        s3=s3,
        bucket_name=BUCKET_NAME,
        object_key=object_key,
    )

    download_file(
        s3=s3,
        bucket_name=BUCKET_NAME,
        object_key=object_key,
        output_filename=downloaded_filename,
    )

    generate_presigned_download_url(
        s3=s3,
        bucket_name=BUCKET_NAME,
        object_key=object_key,
    )

    test_missing_file(s3, BUCKET_NAME)

    delete_object(
        s3=s3,
        bucket_name=BUCKET_NAME,
        object_key=object_key,
    )

    print("\nVerificando después de borrar...")

    if object_exists(s3, BUCKET_NAME, object_key):
        print("El archivo sigue existiendo.")
    else:
        print("El archivo fue eliminado correctamente.")

    if os.path.exists(local_filename):
        os.remove(local_filename)

    if os.path.exists(downloaded_filename):
        os.remove(downloaded_filename)

    print("\nPrueba finalizada correctamente.")


if __name__ == "__main__":
    main()