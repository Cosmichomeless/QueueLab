# Notas originales del proyecto

Texto de partida del proyecto, conservado como referencia de objetivos y conceptos a estudiar.

2. QUEUELAB — Distributed Job Processing Platform
   Proyecto centrado en backend, arquitectura y sistemas distribuidos.
Objetivo:
Crear una plataforma donde se envíen trabajos pesados para ser procesados de forma asíncrona.
Ejemplos:
- Procesamiento de CSV
- Conversión de archivos
- Procesamiento de imágenes
- Análisis por lotes
Arquitectura aproximada:
Client
→ REST API
→ Job Service
→ Message Queue
→ Workers
→ Database / Storage
Stack:
- Java
- Spring Boot
- PostgreSQL
- RabbitMQ o Kafka
- Redis
- Docker
Frontend:
- Dashboard sencillo con Next.js
Estados:
- Queued
- Running
- Completed
- Failed
- Retrying
Conceptos que quiero estudiar:
- Message queues
- Workers
- Procesamiento asíncrono
- Idempotencia
- Retry strategies
- Exponential backoff
- Dead-letter queues
- Concurrencia
- Race conditions
- Rate limiting
- Caching
- Fault tolerance
- Sistemas distribuidos
- Observabilidad
Posibles tecnologías adicionales:
- OpenTelemetry
- Prometheus
- Grafana
3.
