# Secretos, TLS y exposición de la API (#59)

Cómo se inyectan las credenciales fuera de Git, cómo se sirve todo por HTTPS y quién puede llegar a la API. No hay
ningún entorno público creado ([`services-and-costs.md`](services-and-costs.md)): esto es una configuración **probada
en local** que queda lista para usarse, no un despliegue.

## Uso

```bash
cp .env.production.example .env.production          # no se versiona
# Rellene las contraseñas (openssl rand -base64 24) y el hash bcrypt de acceso (ver los comentarios del fichero)
docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.production up -d --wait
```

Con los valores de ejemplo (`QUEUELAB_DOMAIN=localhost`, puerto 8443) queda en `https://localhost:8443`.

## Qué cambia respecto al Compose de desarrollo

| Aspecto | Desarrollo (`docker-compose.yml`) | Público (`docker-compose.prod.yml`) |
| --- | --- | --- |
| Contraseñas de PostgreSQL, RabbitMQ y Grafana | Valor por defecto `queuelab` / `admin` | **Sin valor por defecto**: si falta, Compose no arranca |
| Puertos en el host | Todos en `127.0.0.1` | Ninguno de los servicios de datos, la API ni el dashboard; solo Caddy |
| Entrada | Dashboard y API en puertos distintos, HTTP | Un único origen HTTPS; `/` al dashboard y `/api/v1/*` a la API |
| Acceso | Libre | Usuario y contraseña (Basic) antes de llegar a nada |
| CORS | Orígenes de `localhost` | El propio origen público (no se usa: mismo origen) |

## Secretos fuera de Git

- `.env.production` está en `.gitignore`. Se comprobó que **no lo estaba** (el patrón `.env.*.local` no lo cubre) y
  se corrigió en esta issue.
- `.env.production.example` es lo único versionado y tiene **todos los secretos vacíos**. Con él sin rellenar, Compose
  se niega a arrancar (`required variable ... is missing a value`).
- Las imágenes no llevan credenciales: `docker image inspect` de `queuelab-api`, `queuelab-worker` y
  `queuelab-dashboard` no muestra ninguna variable con `password` o `secret`. Llegan en tiempo de ejecución desde el
  entorno de Compose.
- Las contraseñas se generan, no se eligen: `openssl rand -base64 24`. El acceso se guarda como **hash bcrypt**
  (`caddy hash-password`), no en claro.

**Rotación.** Cambiar `POSTGRES_PASSWORD` o `RABBITMQ_PASSWORD` en `.env.production` **no** cambia la contraseña de un
volumen ya creado: las imágenes de PostgreSQL y RabbitMQ solo la aplican al inicializar los datos. Para rotarla hay que
cambiarla también dentro del servicio (`ALTER USER`, `rabbitmqctl change_password`) y después recrear la API y el
worker. Es el comportamiento conocido de las imágenes; **no se ha probado una rotación en este repositorio**.

## HTTPS

Caddy termina TLS. Con un dominio real obtiene y renueva solo el certificado (Let's Encrypt); con `localhost` usa su
propia autoridad interna.

- **Verificado:** conexión `TLSv1.3` a `https://localhost:8443`, con certificado de la CA interna de Caddy; el
  navegador cargó el dashboard y llamó a `https://localhost:8443/api/v1/jobs` sin errores de consola, es decir, sin
  contenido mixto ni CORS.
- **No verificado:** un certificado real de Let's Encrypt. Exige un dominio y los puertos 80 y 443 alcanzables desde
  Internet; no se dispone de ninguno. Tampoco su renovación.
- La redirección de HTTP a HTTPS pasa a `https://localhost/` sin el puerto cuando se usan puertos no estándar (8080 y
  8443). Con 80 y 443 reales no ocurre. Los servicios internos (API, base de datos, broker) hablan **sin TLS dentro de la
  red de Compose**: no salen de la máquina.

## Política de acceso a la API

La API **no tiene autenticación ni autorización propias** (hallazgo H3 de la
[revisión de seguridad](../security/upload-api-review.md)). Por eso la política es:

1. **La API nunca se publica directamente.** Solo es alcanzable a través de Caddy; sus puertos no se publican y
   `/actuator` y cualquier ruta distinta de `/api/v1/*` devuelven 404 desde fuera (verificado).
2. **Sin credenciales no se llega ni al dashboard ni a la API.** Verificado: `/` y `/api/v1/jobs` devuelven 401 sin
   credenciales y con credenciales erróneas; 200 con las correctas.
3. **Por defecto Caddy solo escucha en `127.0.0.1`.** Abrirlo a la red es una decisión explícita
   (`QUEUELAB_BIND=0.0.0.0`) y solo debe tomarse con un dominio y con las contraseñas de `.env.production` ya
   generadas.
4. **No subir datos personales ni confidenciales.** El almacenamiento no está cifrado y no tiene cuota total (H7).

**Límites de esta política** (sin disimular):

- Es **una sola cuenta compartida**: no hay usuarios, roles, auditoría por persona ni revocación individual.
- No se ha comprobado que Caddy limite los intentos de contraseña: sin ese límite, la contraseña debe ser larga y
  aleatoria.
- Tras Caddy, la API ve la IP del proxy: según H4, el límite de 60 envíos por minuto se vuelve **global** para todos
  los clientes. No se ha medido en esta configuración.
- No resuelve H3 en el código: es una puerta delante, no autorización dentro de la aplicación.

## Verificado en esta issue

Con `docker compose -p queuelab-prod-test ... up -d --wait --build` (proyecto aparte, volúmenes propios, borrados al
terminar), en macOS con Docker Desktop:

- Los 7 servicios (PostgreSQL, RabbitMQ, Redis, API, worker, dashboard y Caddy) arrancan; Caddy aún no tenía
  healthcheck (se añadió en la #61) y se comprobó con `Up` y con las peticiones de abajo.
- Sin `.env.production`, o con el de ejemplo sin rellenar, `docker compose config` falla.
- La contraseña de desarrollo `queuelab` es **rechazada** por PostgreSQL y por RabbitMQ; la generada, aceptada.
- Un CSV subido por `https://localhost:8443/api/v1/jobs/csv` con credenciales llega a `COMPLETED`.
- Las imágenes y el historial de Git no contienen la contraseña generada.

**No verificado:** certificado real y su renovación, Linux, rotación de contraseñas, límite de intentos de acceso y el
comportamiento de la cuota por IP detrás del proxy. El perfil `observability` con esta configuración se probó después,
en la [#61](https://github.com/Cosmichomeless/QueueLab/issues/61), y destapó dos defectos que se corrigieron allí
(Prometheus sin objetivos y Grafana con acceso anónimo): ver [`deploy-compose.md`](deploy-compose.md).
