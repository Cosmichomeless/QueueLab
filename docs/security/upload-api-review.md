# Revisión de seguridad: subida de ficheros y API

Revisión (issue #51) de la superficie que recibe datos de fuera: `POST /api/v1/jobs/csv`, el resto de
`/api/v1/**`, los endpoints de actuator, el almacenamiento local y la ejecución del CSV en el worker. El criterio
era doble: **se rechazan los ficheros fuera de límites y los nombres peligrosos**, y **ni los errores ni los logs
filtran secretos ni contenido de ficheros**.

Cómo se hizo: lectura del código de cada capa por la que pasa un fichero (controlador, `CsvUploadService`,
`LocalFileStorage`/`StorageNames`, `ApiExceptionHandler`, `CorsConfig`, límite de envíos, `CsvImportJobHandler`
y `CsvRecordReader`), tests nuevos que intentan romper cada límite y ejecución contra el contenedor real
(Tomcat) para lo que MockMvc no ve. Los hallazgos están en la sección 3; los dos arreglos de bajo riesgo ya
están aplicados.

## 1. Qué se comprobó y cómo queda

| Control | Dónde | Resultado | Prueba |
|---|---|---|---|
| Tamaño máximo del fichero (10 MiB, `queuelab.csv.max-file-size`) | multipart (contenedor) + `CsvUploadService` | `413`, nada guardado | `CsvUploadLimitTest` (ya existía) |
| Tamaño máximo de la petición (`max-request-size`, 11 MB) | contenedor | `413` sin guardar nada ni dejar traza | `UploadTransportSecurityTest.aRequestOverTheRequestLimitIsRejectedWith413` |
| Nombre del cliente **nunca** llega al sistema de ficheros | `CsvUploadService` guarda `<uuid>.csv`; el nombre solo se usa para reconocer la extensión | Con `../..`, rutas absolutas y de Windows, `%2e%2e`, byte nulo, salto de línea, HTML, SQL, RTL y 5000 caracteres: `201`, un único fichero `<uuid>.csv`, nada fuera de la zona `inputs/` | `UploadSecurityTest.aDangerousClientNameNeverReachesTheFileSystem` y `aVeryLongClientNameIsHarmless` |
| Solo CSV | `declaredAsCsv`: `Content-Type` compatible con `text/csv` **o** nombre acabado en `.csv` | `shell.sh`, `x.csv.exe`, `x.csv\0.exe`, `datos.csv␠`, `text/html`, `application/zip`, `image/svg+xml`… → `415` sin guardar nada | `UploadSecurityTest` (dos tests parametrizados) |
| Contenido mínimo válido | UTF-8 estricto de la cabecera, no vacía, ≤ 64 KiB | `400` sin guardar | `CsvUploadTest` (ya existía) |
| Doble barrera de rutas en el almacenamiento | `StorageNames` (lista blanca `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`, sin `..`) + `normalize()`/`startsWith` + `toRealPath` contra enlaces simbólicos | Intentos con `..`, absolutas, separadores, enlaces a fuera → `InvalidStorageReferenceException` | `LocalFileStorageTest` (ya existía) |
| Ids de ruta | `@PathVariable UUID` | Cualquier otra cosa (`..`, `%2F`, SQL) → `400/404`, sin tocar el almacenamiento | `UploadSecurityTest.jobIdsAreOnlyEverUuids`, `UploadTransportSecurityTest.encodedTraversalInThePathIsAClientError` |
| Multipart mal formado | contenedor + `ApiExceptionHandler` | `400` genérico (antes `500`, ver H1) | `UploadTransportSecurityTest` |
| Errores sin internals | `ApiExceptionHandler` (`application/problem+json`; el `500` es genérico) | Sin trazas, rutas ni clases en el cuerpo | `ApiErrorsTest` (ya existía), `UploadTransportSecurityTest` |
| Ni nombre ni contenido en respuestas, BD o logs | `JobController.logAccepted` (solo id y tipo), mensajes de error estáticos | Una marca en el nombre y otra en las celdas no aparecen en respuestas (`201`, `400`, `415`), filas de `jobs` y `outbox_events` ni en la salida de log | `UploadSecurityTest.neitherTheNameNorTheContent…` y `rejectionsDoNotEcho…` |
| Errores del worker sin contenido | `CsvFormatException`: solo número de línea y motivo | Una fila con el número de campos equivocado falla con `Línea 3: tiene 3 campos y la cabecera 2`, sin el valor de la fila (el test comprueba que no aparece) | `CsvImportTest` (ya existía) |
| Endpoints de gestión | `management.endpoints.web.exposure.include: health,info` | `env`, `beans`, `heapdump`, `threaddump`, `configprops`, `mappings`, `loggers`, `shutdown`, `conditions`, `caches`, `scheduledtasks`, `flyway`, `sbom` → `404`; `/actuator/health` no muestra componentes, URLs ni detalles | `UploadSecurityTest` |
| Cabeceras | `X-Content-Type-Options: nosniff` en todas las respuestas (H2) | Presente también en errores y actuator | `UploadSecurityTest.everyResponseCarriesNosniff` |
| Secretos en el repositorio | `application*.yml`, `.env.example`, `docker-compose.yml` | Los valores productivos son vacíos y salen de variables de entorno; las contraseñas `queuelab` solo aparecen en el perfil `local`, en el `.env.example` y como valor por defecto del compose de desarrollo (puertos en `127.0.0.1`); `.env*` está en `.gitignore` | Revisión manual (grep) |
| Dependencias del dashboard | `npm audit --omit=dev` | 0 vulnerabilidades en las dependencias de producción | Ejecutado el 2026-10-06 |

## 2. Cómo se comporta lo que no es evidente

- **El nombre del cliente no es de fiar y el sistema no confía en él**: ni se guarda, ni se registra, ni se
  devuelve. La descarga del resultado usa un nombre generado por el servidor (`<uuid>.stats.json`), no uno del cliente,
  así que no hay inyección en `Content-Disposition`.
- **Un único límite por capas**: el contenedor corta la subida al llegar a `max-file-size` y el servicio la vuelve a
  comprobar; la cabecera se lee en un máximo de 64 KiB y el resto del fichero no se carga en memoria (se copia en
  streaming al almacenamiento).
- **El worker es estricto y acotado**: campos de ≤ 1 MiB, ≤ 100 columnas, nombres de columna ≤ 100 caracteres,
  UTF-8 estricto, lectura en streaming (memoria independiente del tamaño del fichero). No se descomprime nada, así
  que no hay bombas de compresión; no hay `eval`, plantillas ni consultas construidas con el contenido.
- **Los errores del contenedor ya eran 4xx salvo el multipart ilegible** (H1): el límite de tamaño, las partes de
  más y las rutas con barras codificadas se resuelven con `400/404/413` sin pasar por la red de seguridad.

## 3. Hallazgos

### Arreglados en este cambio

**H1. Un multipart ilegible respondía `500` y registraba una traza.** Sin `boundary`, con el cuerpo truncado o
con cualquier cosa que Tomcat no pudiera leer, la excepción (`MultipartException`) caía en el manejador genérico
`Exception`: respuesta `500 Error interno` (equivocada: es un error del cliente) y un `ERROR` con traza completa en
el log por cada petición malformada. Cualquiera podía llenar el log de trazas y disparar alertas de errores 5xx.
*Arreglo:* `ApiExceptionHandler.malformedMultipart` responde `400 «La petición multipart no es válida»` y deja el
motivo solo en `debug`. El caso del límite de tamaño sigue yendo a su manejador propio (`413`), que es más
específico. *Prueba:* `UploadTransportSecurityTest` (fallaba con `500` antes del arreglo: sin `boundary` y cuerpo
truncado).

**H2. Sin `X-Content-Type-Options: nosniff`.** La API devuelve ficheros derivados de lo que sube el cliente
(`GET /api/v1/jobs/{id}/result`, JSON con los nombres de columna del CSV). Aunque se sirven como
`application/json` y `attachment`, sin `nosniff` un navegador puede intentar adivinar el tipo.
*Arreglo:* `SecurityHeadersFilter` añade la cabecera a todas las respuestas (también a los errores).

### Documentados, sin cambio de código (decisiones y riesgos aceptados)

**H3. No hay autenticación ni autorización.** Cualquiera que llegue a la API puede subir ficheros, listar y
descargar resultados de cualquier trabajo. Es una decisión de alcance del laboratorio (las issues del proyecto no
incluyen cuentas de usuario), no un descuido, pero es **el riesgo principal** si se expone fuera de una red de
confianza. Mitigaciones existentes: límite de envíos por IP (60/min), tamaño máximo, contrapresión (`503`) y
puertos de infraestructura solo en `127.0.0.1`. Para exponerlo hay que poner delante un proxy con autenticación o
añadir Spring Security; no se hace aquí porque cambia el contrato de la API y el del dashboard.

**H4. El límite de envíos usa `request.getRemoteAddr()`.** Es lo correcto (no se fía de `X-Forwarded-For`, que un
cliente podría falsificar para saltarse la cuota), pero tras un proxy inverso **todos los clientes comparten la IP
del proxy** y la cuota pasa a ser global. Quien despliegue detrás de un proxy debe configurar
`server.forward-headers-strategy` (o `ForwardedHeaderFilter`) **y** que el proxy sobrescriba esas cabeceras. Si
Redis no responde la API deja pasar las peticiones (decisión explícita; ver `backend/README.md`, «Límite de
envíos»), es decir, la cuota no es una barrera de seguridad dura.

**H5. CORS por configuración.** Solo se permiten los orígenes de `QUEUELAB_CORS_ALLOWED_ORIGINS`
(`http://localhost:3000` por defecto), métodos `GET`/`POST`, sin credenciales. Un valor `*` en esa variable
abriría la API a cualquier página; no se bloquea por código porque hoy no hay credenciales que robar (H3), pero
conviene no usarlo en un despliegue real.

**H6. Fórmulas en celdas (CSV injection).** No aplica hoy: el resultado es JSON con estadísticas (nombres de
columna, contadores, mínimos y máximos numéricos), no se vuelve a emitir CSV. Si en el futuro se añade una
exportación en CSV/Excel con valores de las celdas, hay que neutralizar las que empiecen por `=`, `+`, `-`, `@`,
tab o retorno de carro. Los nombres de columna sí viajan al resultado (son datos del propio usuario que subió el
fichero y los ve quien consulte ese trabajo; no incluyen valores de filas).

**H7. Los ficheros se guardan sin cifrar y sin cuota total.** `LocalFileStorage` escribe en disco con los permisos
del proceso; no hay límite al número total de ficheros salvo la contrapresión de la cola (`max-pending`), que
frena los envíos antes de gastar disco. La limpieza de huérfanos (#32) recoge los restos. Cifrado en reposo y
cuotas por cliente quedan para quien opere el despliegue.

**H8. Coste de analizar números enormes (no medido).** El worker acepta campos numéricos de hasta 1 MiB de
dígitos (`[-+]?\d+(\.\d+)?`, sin exponente) y los suma como `BigDecimal`. El coste está acotado por el tamaño del
fichero (10 MiB) y no hay forma de amplificarlo con exponentes, pero no se ha medido cuánto tarda un fichero
patológico de ese tipo; si preocupa, bastaría limitar los dígitos de un campo numérico.

**H9. `npm audit` completo (con dependencias de desarrollo) sigue informando de 5 vulnerabilidades altas.** Son
todas de herramientas de desarrollo (no entran en la imagen del dashboard ni en el bundle: `npm audit --omit=dev`
da 0). No se aplica `npm audit fix --force`, que subiría versiones mayores de ESLint/Next sin revisarlas.

### No se pudo verificar aquí

- Terminación TLS, cabeceras de seguridad del proxy y configuración de red de un despliegue real.
- Antivirus o análisis de contenido del fichero: la API valida formato, no malicia.
- Fuga por logs con formato `logstash`/`ecs` (JSON): la prueba de logs usa el formato de texto; los campos del
  JSON salen del mismo MDC (`correlationId`, `jobId`), que no contiene datos del fichero.

## 4. Tests añadidos

- `backend/api/src/test/java/com/queuelab/api/UploadSecurityTest.java` (51 casos): nombres peligrosos, tipos que no
  son CSV, ausencia de nombre y contenido en respuestas/BD/logs (aceptados y rechazados), `nosniff`, endpoints de
  actuator y ids de ruta.
- `backend/api/src/test/java/com/queuelab/api/UploadTransportSecurityTest.java` (10 casos, servidor real en puerto
  aleatorio): multipart sin `boundary`, truncado o no multipart, petición mayor que el límite, demasiadas partes y
  rutas con barras codificadas.

Se ejecutan con el resto de la suite de la API (`./mvnw -pl core,api test`, necesita Docker para PostgreSQL).

## 5. Cambios de código

- `backend/api/src/main/java/com/queuelab/api/error/ApiExceptionHandler.java`: manejador de `MultipartException` → `400`.
- `backend/api/src/main/java/com/queuelab/api/SecurityHeadersFilter.java`: `X-Content-Type-Options: nosniff`.

No se tocan poms ni `application.yml`.
