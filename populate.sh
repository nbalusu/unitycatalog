bin/uc catalog create --name catalog_naresh --server http://localhost:8086


bin/uc schema create  --catalog catalog_naresh --name schema_naresh --server http://localhost:8086


bin/uc table create --full_name catalog_naresh.schema_naresh.myTable --columns "col1 int, col2 double" --storage_location /tmp/uc/myTable --server http://localhost:8086



bin/uc table list --catalog catalog_naresh --schema schema_naresh --server http://localhost:8086
