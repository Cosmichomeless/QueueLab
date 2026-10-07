# Servicios de despliegue y costes (#58)

Decisión sobre dónde y cómo se ejecutaría QueueLab fuera del portátil, con un presupuesto de **0 €**: es un proyecto
de exposición en GitHub y no se va a pagar ningún servicio. Este documento no despliega nada: elige, estima y fija
límites. Lo que sigue sin existir está marcado como tal.

## Decisión

| Pregunta | Decisión |
| --- | --- |
| ¿Hay un entorno alojado permanente? | **No.** La exposición es el repositorio: README, capturas, CI y un stack que arranca con un comando. |
| ¿Cómo lo prueba otra persona sin instalar nada? | **GitHub Codespaces** (opcional): el mismo `docker-compose.yml` en una máquina de GitHub. Sin tarjeta, dentro de la cuota gratuita. |
| ¿Y si algún día se quiere una URL pública? | **Una sola VM con el mismo Compose** (opción documentada: Oracle Cloud Always Free, Ampere A1). **No está creada** y exige tarjeta de crédito para verificar la cuenta. |
| ¿Servicios gestionados (PostgreSQL, broker, Redis)? | **No.** Ninguno tiene capa gratuita que cubra los tres sin límites que rompan el diseño (ver «Por qué no PaaS»). |

La razón de fondo: QueueLab son seis procesos con estado (PostgreSQL, RabbitMQ, Redis, API, worker, dashboard) y un
volumen compartido entre API y worker. Ya se ejecutan juntos con Compose; la opción más barata y más fiel a lo
verificado es **no cambiar la topología** y solo cambiar dónde corre.

## Topología

La misma que [`compose-stack.md`](../compose-stack.md), sin servicios gestionados:

```
Navegador ──► dashboard (Next.js, 3000) ──► API (8080) ──► PostgreSQL (outbox)
                                              │  └──────────► Redis (cuota por IP)
                                              └─► RabbitMQ ──► worker ──► PostgreSQL
                         volumen storage-data compartido por API y worker
```

Con una VM, todo vive en la misma máquina y en la red `queuelab` de Compose. Solo el dashboard y la API necesitarían
puerto público; PostgreSQL, RabbitMQ y Redis **no se publican** fuera de la red de Compose.

## Quién opera qué

| Pieza | Quién la opera | Dónde guarda su estado | Qué pasa si falla |
| --- | --- | --- | --- |
| PostgreSQL | El propio proyecto (contenedor `postgres:18-alpine`) | Volumen `postgres-data` | Fuente de verdad: sin ella la API no funciona. Sin copias de seguridad automáticas. |
| RabbitMQ (broker) | El propio proyecto (contenedor `rabbitmq:4`) | Volumen `rabbitmq-data` | Las publicaciones esperan en el outbox y se reintentan; no se pierden trabajos. |
| Redis | El propio proyecto (contenedor `redis:7`) | Ninguno (con expiración) | La API deja de limitar envíos (H4); no se pierden datos. |
| Almacenamiento de ficheros | El propio proyecto: **volumen Docker local** `storage-data` | Disco de la VM o del codespace | Un disco por máquina: API y worker deben estar en el **mismo host**. |
| API, worker, dashboard | El propio proyecto | Sin estado propio | Se reinician solos con `restart` y se recuperan por lease (2 min). |

Nadie externo opera nada: no hay proveedor de broker ni de almacenamiento de objetos. Eso es lo que cuesta 0 €, y
también lo que implica que **las copias de seguridad, las actualizaciones y la monitorización son responsabilidad de
quien despliega**.

## Costes estimados

| Opción | Coste mensual | Qué hace falta |
| --- | --- | --- |
| Local (Docker Desktop o Docker Engine) | 0 € | Una máquina con Docker |
| GitHub Codespaces | 0 € dentro de la cuota | Cuenta personal de GitHub; sin tarjeta |
| VM Oracle Cloud Always Free (A1) | 0 € mientras se respeten los límites | **Tarjeta de crédito** para verificar; ver riesgos |
| PaaS de pago (Render, Railway, Fly.io, etc.) | No estimado | Descartado por el presupuesto |

Los dominios propios y los certificados de pago no se contemplan. Con una IP pública sin dominio no hay HTTPS válido:
ver [#59](https://github.com/Cosmichomeless/QueueLab/issues/59).

## Límites de las opciones gratuitas

Cifras tomadas de la documentación y de artículos consultados el 2026-10-07. **No se ha abierto una cuenta ni se ha
comprobado en la consola**; las cuotas cambian y deben revisarse antes de depender de ellas.

**GitHub Codespaces** ([docs de facturación](https://docs.github.com/en/billing/managing-billing-for-your-products/managing-billing-for-github-codespaces)):

- Cuenta personal GitHub Free: **120 horas de núcleo al mes y 15 GB-mes de almacenamiento**. Una máquina de 4 núcleos
  consume 4 horas de núcleo por hora activa, es decir, unas **30 horas al mes**; una de 2 núcleos, unas 60.
- Las cuentas de organización en el plan Free no incluyen uso gratuito: tiene que ser el repositorio de una cuenta
  personal o usarse desde ella.
- Un codespace se suspende por inactividad: **no sirve como demo permanente**, solo para que alguien lo pruebe.
- Con 2 núcleos y poca memoria, la primera construcción (JDK 25, Maven, npm) será lenta; no se ha probado.

**Oracle Cloud Always Free, Ampere A1** ([recursos](https://docs.oracle.com/en-us/iaas/Content/FreeTier/resourceref.htm),
[recorte de junio de 2026](https://linuxiac.com/oracle-quietly-cuts-free-tier-ampere-a1-resources-in-half/)):

- Según lo consultado, desde el 15 de junio de 2026 el A1 gratuito es de **2 OCPU y 12 GB de RAM** (antes 4 y 24 GB),
  con 200 GB de almacenamiento en bloque en total y 10 TB de salida al mes. El recorte lo cuentan fuentes de prensa
  técnica; no se ha contrastado con la consola de Oracle.
- Oracle puede **reclamar instancias inactivas** si durante 7 días el percentil 95 de CPU, red y memoria es inferior al
  20 %. Un proyecto de exposición sin tráfico encaja exactamente en ese caso.
- La capacidad A1 suele estar agotada en varias regiones y el alta puede fallar por falta de capacidad.
- Pide tarjeta para verificar la identidad. Según lo consultado no cobra mientras no se actualice a cuenta de pago,
  pero **es una condición que choca con «no pagar nada»**: decide quien tenga la tarjeta.
- La máquina es `arm64`. Se comprobó que `eclipse-temurin:25-jre-alpine` publica `linux/arm64/v8`; no se comprobó
  `postgres`, `rabbitmq`, `redis` ni la base del dashboard. El stack se verificó en macOS con Apple Silicon (arm64)
  y Docker Desktop, **no en Linux**.

## Dimensionado

Medido en la [#61](https://github.com/Cosmichomeless/QueueLab/issues/61), con una máquina de 7,65 GiB (macOS, arm64):
`docker-compose.prod.yml` fija un límite por servicio cuya suma es **≈ 2,4 GiB** (picos medidos bajo carga entre 8 y
271 MiB por servicio). Cabe con holgura en 8 GB (Codespaces) y en 12 GB (Oracle), **pero no se ha probado en ninguno de
los dos**. Tablas, método y salvedades en [`deploy-compose.md`](deploy-compose.md). Sin límite, cada JVM tomaría hasta el
75 % de la memoria que viera (`-XX:MaxRAMPercentage=75`, [`containers-backend.md`](../containers-backend.md)).

Para la capacidad, el rendimiento medido (8 hilos ≈ 40 trabajos/s en un M4 Pro) **no es extrapolable** a 2 OCPU
ARM: ver [`performance/capacity.md`](../performance/capacity.md).

## Por qué no PaaS ni servicios gestionados

No se ha evaluado cada proveedor uno a uno; el criterio de descarte es el diseño y el coste cero:

- El worker no tiene HTTP de negocio y debe estar siempre encendido: las capas gratuitas que duermen el servicio por
  inactividad lo dejan sin consumir y los trabajos se acumulan.
- API y worker comparten un **volumen**. Muchos PaaS no permiten un volumen compartido entre servicios; habría que
  cambiar el almacenamiento a un objeto remoto, que es un cambio de código y de diseño, no de despliegue.
- Hacen falta tres servicios de datos distintos (PostgreSQL, RabbitMQ con confirmaciones de publicación y Redis). Que
  los tres tengan capa gratuita y cuota suficiente a la vez no está comprobado.

## Qué cambia en las issues siguientes

- [#59](https://github.com/Cosmichomeless/QueueLab/issues/59) (secretos, TLS y exposición): sin una API pública
  permanente, el riesgo H3 (sin autenticación) no se expone. Si se abre una URL pública, hay que decidir la política de
  acceso **antes**.
- [#60](https://github.com/Cosmichomeless/QueueLab/issues/60) (datos y migraciones): los datos viven en volúmenes de
  Compose; faltan copia y restauración, que habría que documentar y probar.
- [#61](https://github.com/Cosmichomeless/QueueLab/issues/61) (hecha) a [#63](https://github.com/Cosmichomeless/QueueLab/issues/63):
  con esta decisión, el «despliegue» es el Compose probado en local ([`deploy-compose.md`](deploy-compose.md)); un
  entorno público real requeriría crear la VM, que **necesita una decisión y la cuenta del propietario**.

## Resumen de lo verificado

- **Verificado en este repositorio:** la topología y los volúmenes (`docker-compose.yml`, `compose-stack.md`), el
  `MaxRAMPercentage` de las imágenes y, con la #61, el consumo de memoria bajo carga y los límites por servicio.
- **Consultado, no comprobado en cuenta real:** las cuotas de Codespaces y de Oracle Always Free y la política de
  reclamación de instancias.
- **No verificado:** ejecución en Linux o en `amd64`, el consumo en una máquina de 8 o 12 GB, tiempo de construcción en
  un codespace de 2 núcleos y el alta en Oracle.
