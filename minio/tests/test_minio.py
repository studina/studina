import boto3
from botocore.client import Config

s3 = boto3.client(
    "s3",
    endpoint_url="http://localhost:9000",
    aws_access_key_id="studina_admin",
    aws_secret_access_key="studina_admin_123456",
    config=Config(signature_version="s3v4"),
    region_name="us-east-1",
)

bucket = "studina-documents"

existing_buckets = [b["Name"] for b in s3.list_buckets()["Buckets"]]
if bucket not in existing_buckets:
    s3.create_bucket(Bucket=bucket)

with open("apunte.txt", "w", encoding="utf-8") as f:
    f.write("Este sería un apunte subido por un usuario de Studina.")

s3.upload_file("apunte.txt", bucket, "users/1/documents/apunte.txt")

objects = s3.list_objects_v2(Bucket=bucket)

print("Objetos en el bucket:")
for obj in objects.get("Contents", []):
    print("-", obj["Key"])

s3.download_file(bucket, "users/1/documents/apunte.txt", "apunte-descargado.txt")

print("Archivo descargado correctamente.")