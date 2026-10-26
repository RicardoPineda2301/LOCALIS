# LOCALIS

Asistente de IA **100% local** para entender, documentar y programar sobre código legacy. Corre en la máquina del
usuario, sin internet, sobre [Ollama](https://ollama.com).

> **Estado: experimental.** Probado en Windows. macOS y Linux deberían funcionar (Java + Node), pero no están probados.

## Qué hace

| Función | Descripción |
|---|---|
| Indexación | Indexa el repositorio (Java por método; el resto por ventanas de líneas). Búsqueda híbrida BM25 + embeddings. |
| Explicar / preguntar | Responde sobre el código del repositorio con citas `fichero:línea` verificadas contra el índice. |
| Diagnosticar error | A partir de un error o stack trace, propone causas probables con evidencia. No corrige código. |
| Programar | Conversación directa con el modelo, sin limitarse al repositorio indexado. |
| Documentación | Rellena con IA las plantillas `.docx` elegidas (texto, tablas y diagramas), quita los apartados que no aplican y renumera. Varias plantillas = un `.zip`. |

## Stack

| Capa | Tecnología |
|---|---|
| Backend | Java 25, `com.sun.net.httpserver` (API REST sin framework), Jackson, JavaParser, SQLite (índice), build con Maven |
| Frontend | React 19, TypeScript, Vite; se compila dentro del jar y lo sirve el propio backend |
| Modelos | Ollama: chat `qwen2.5-coder` (7b / 14b) y embeddings `nomic-embed-text` |
| Documentos | Plantillas `.docx` en `templates/`, editadas a nivel XML; diagramas dibujados con Java2D |

## Prerrequisitos

| Herramienta | Versión | Para qué |
|---|---|---|
| JDK | **25** | Compilar y ejecutar (`maven.compiler.release=25`) |
| Maven | 3.9+ | Compilar el backend |
| Node.js | 22 LTS (mín. 20.19) | Compilar el frontend (Vite 8 y TypeScript 7 no funcionan con Node 16) |
| Ollama | reciente | Motor de IA local; debe estar en marcha (`http://localhost:11434`) |
| Memoria | 8 GB+ | 8-16 GB: `qwen2.5-coder:7b`; 16-24 GB: `qwen2.5-coder:14b` |

El modelo por defecto del código (`mistralai/Devstral-Small-2-24B-Instruct-2512`) exige mucha memoria: en equipos
normales hay que cambiarlo en `localis.properties` (ver abajo).

## Puesta en marcha

```
ollama pull qwen2.5-coder:7b
ollama pull nomic-embed-text

cd frontend && npm install && npm run build && cd ..       # genera src/main/resources/web
mvn -B -q clean package -DskipTests                         # genera target/localis.jar

cp localis.properties.example localis.properties            # Windows: copy ...
# en localis.properties: ollama.chatModel=qwen2.5-coder:7b
java --enable-native-access=ALL-UNNAMED -jar target/localis.jar localis.properties
```

Abra <http://127.0.0.1:8080>, pulse **Configurar**, elija el repositorio y **Indexar**.
Tras cambiar código del frontend hay que repetir `npm run build` y `mvn package`.

Tests: `mvn test` (backend) y `cd frontend && npm test`.

## Configuración

Todo en `localis.properties` (plantilla: [localis.properties.example](localis.properties.example)). Cada clave también
se puede definir con una variable de entorno `LOCALIS_<CLAVE_EN_MAYUSCULAS>`. Las más útiles:

| Clave | Para qué |
|---|---|
| `ollama.chatModel` / `ollama.embedModel` | Modelos de chat y de embeddings |
| `ollama.numCtx`, `ollama.maxTokens` | Contexto del modelo y longitud máxima de cada respuesta |
| `repo.path` | Repositorio a analizar (también se elige desde la interfaz) |
| `branding.company`, `branding.logo` | Empresa y logo que se ponen en los documentos |
| `license.enforce` | `false` en desarrollo; la licencia solo se exige en las builds para clientes |

## Modo sin internet

El servidor escucha solo en `127.0.0.1` y toda conexión saliente pasa por `NetworkGuard`: únicamente se permiten los
hosts de `network.allowedHosts` (por defecto, solo la propia máquina). No hay telemetría.

## Estructura

```
src/main/java/com/localis/   api, rag (explicar/diagnosticar), index, search, llm, docs, license, config
frontend/                    React + Vite
templates/                   plantillas .docx
scripts/                     instalador y empaquetado para clientes (Windows)
```

## Licencias (solo builds para clientes)

Licencias offline firmadas con Ed25519 (caducidad, máquinas y módulos `explain`, `diagnose`, `docs`). Se emiten con
`com.localis.license.LicenseTool` (`keygen`, `fingerprint`, `issue`); la clave pública va en
`src/main/resources/license-public.pem`. **Nunca suba `private.pem` ni `license.json`.**

## Pendiente

- Que el bot respete las convenciones del repositorio del usuario (perfil del proyecto + documentación indexada).
- Rehacer "Diagnosticar" como auditoría del sistema (mejoras, optimizaciones, vulnerabilidades).
- Más contexto para los documentos generados (manifiestos, esquema de BD, módulos).
