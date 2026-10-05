# QueueLab backend

Monorepo Maven con dos procesos independientes:

| Módulo | Artefacto | Rol |
|---|---|---|
| `api` | `queuelab-api` | API REST (HTTP, health check en `/actuator/health`) |
| `worker` | `queuelab-worker` | Proceso de fondo sin servidor HTTP que consume trabajos |

## Compilar API y worker

Requiere JDK 25. Un solo comando compila y prueba ambos módulos:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)   # macOS
cd backend && ./mvnw verify
```

## Arrancar

```bash
java -jar backend/api/target/queuelab-api-0.1.0-SNAPSHOT.jar
java -jar backend/worker/target/queuelab-worker-0.1.0-SNAPSHOT.jar
```
