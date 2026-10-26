# LOCALIS

Asistente de IA **100% local** para comprender, documentar y diagnosticar código legacy. Corre en la máquina del cliente,
sin internet, sobre [Ollama](https://ollama.com). Backend en Java 21 y frontend web (React) servido por el propio jar.

## Qué hace

| Módulo | Endpoint | Descripción |
|---|---|---|
| Indexación | `POST /api/index`, `GET /api/index/status` | Indexa el repositorio del cliente de forma incremental (Java por método; SQL, .NET y otros por ventanas de líneas). Búsqueda híbrida BM25 + embeddings. |
| Explicar | `POST /api/explain` | `{"path": "src/.../Foo.java"}` y/o `{"question": "..."}`. Explica un fichero (por partes si es muy grande) o responde preguntas sobre el código. |
| Diagnosticar | `POST /api/diagnose` | `{"problem": "la facturación genera mal el total"}`. Devuelve hipótesis con evidencia `[fichero:línea]`. No corrige código. |
| Documentación | `GET /api/docs/templates`, `POST /api/docs/generate` | Genera el `.docx` de una plantilla con el logo, empresa, proyecto, versión y autor del cliente. |
| Documentación automática | `POST /api/docs/generate-auto`, `GET .../status`, `GET .../result` | Rellena con IA las plantillas elegidas (texto, tablas y diagramas) a partir del repositorio indexado; elimina los apartados que no aplican y renumera. Con varias plantillas devuelve un `.zip`. Se ejecuta en segundo plano con progreso. |
| Programar | `POST /api/job` con `{"type":"code","messages":[...]}` | Conversación directa con el modelo, sin limitarse al repositorio indexado. |
| Estado | `GET /api/health` | Estado de Ollama, modelos instalados, índice y licencia. |

### Citas verificadas
El modelo no "adivina" líneas: cada cita `fichero:línea` de la respuesta se comprueba contra el índice (existe el fichero,
existen las líneas **y el modelo las tuvo delante**). Si alguna falla se le pide una corrección una vez; lo que siga sin
verificarse se marca en `citations[].status` y en `warnings`.

## Modo sin internet
- El servidor escucha solo en `127.0.0.1` por defecto.
- Toda conexión saliente pasa por `NetworkGuard`: solo se permiten los hosts de `network.allowedHosts`
  (por defecto `localhost`, `127.0.0.1`, `::1`). No se siguen redirecciones.
- No hay telemetría ni llamadas externas. Para permitir otro host (p. ej. un servidor Ollama en la LAN) el cliente
  debe añadirlo explícitamente.

## Requisitos
- Java 21 (para compilar: Maven 3.9+).
- Ollama con un modelo de chat y uno de embeddings, por ejemplo:
  ```
  ollama pull mistralai/Devstral-Small-2-24B-Instruct-2512
  ollama pull nomic-embed-text
  ```
  Los modelos se cambian en `localis.properties` (`ollama.chatModel`, `ollama.embedModel`). **Revise la licencia de cada
  modelo antes de distribuirlo comercialmente.**

## Rama `experimental`
Rama para probar y desarrollar sin compromisos: no lleva claves, licencias, rutas ni datos de ningún cliente.
`localis.properties`, `license.json`, `data/` y las claves están en `.gitignore`; cada desarrollador usa los suyos.

Arranque rápido (Windows, macOS o Linux; requiere Java 21+, Maven 3.9+, Node 20+ y Ollama):
```
ollama pull qwen2.5-coder:7b          # o qwen2.5-coder:14b si hay memoria; y el de embeddings:
ollama pull nomic-embed-text
cd frontend && npm install && npm run build && cd ..      # el frontend se empaqueta dentro del jar
mvn -B -q clean package -DskipTests
cp localis.properties.example localis.properties           # Windows: copy ...
# edite localis.properties: ollama.chatModel=qwen2.5-coder:7b  (el valor por defecto, Devstral 24B, exige mucha memoria)
java --enable-native-access=ALL-UNNAMED -jar target/localis.jar localis.properties
```
Abra http://127.0.0.1:8080, pulse **Configurar** y elija el repositorio a analizar.

Orientación de modelos: 8-16 GB de memoria -> `qwen2.5-coder:7b`; 16-24 GB -> `qwen2.5-coder:14b`.
`license.enforce=false` por defecto: la licencia solo se exige en las builds para clientes.

## Uso
```
mvn package                       # compila, ejecuta tests y genera target/localis.jar
copy localis.properties.example localis.properties   # y ajuste repo.path, branding.*, etc.
java -jar target/localis.jar
curl -X POST http://127.0.0.1:8080/api/index
curl -X POST -H "Content-Type: application/json" -d "{\"problem\":\"el total de la factura sale mal\"}" http://127.0.0.1:8080/api/diagnose
```

## Plantillas
[templates/](templates) contiene las plantillas `.docx` neutras (sin logo ni marca). Campos que se rellenan:
`{{COMPANY_NAME}}` (pie), `[Project Name]`, `[Month Year]`, `[Author Name]`, `[Team / Area]` y el `[X.X]` de la portada.
El logo del cliente (`branding.logo`: PNG, JPG, GIF o BMP) se ajusta sin deformarlo al lienzo del logo de la plantilla.
El resto de marcadores `[...]` quedan para que la IA los complete (siguiente fase).

## Licencias (herramienta del proveedor)
Licencias offline firmadas con Ed25519, con caducidad (o vitalicias), máquinas y módulos (`explain`, `diagnose`, `docs`).
```
java -cp target/localis.jar com.localis.license.LicenseTool keygen ./keys          # una sola vez; guarde private.pem fuera del repo
java -cp target/localis.jar com.localis.license.LicenseTool fingerprint            # el cliente envía este id
java -cp target/localis.jar com.localis.license.LicenseTool issue --key keys/private.pem --customer "ACME" ^
     --expires 2027-10-13 --machine-id <id> --modules explain,diagnose,docs --out license.json
```
Copie `public.pem` a `src/main/resources/license-public.pem` antes de compilar la build de cliente y active
`license.enforce=true`. **Nunca distribuya ni suba `private.pem`.**

## Pendiente / siguientes pasos
- Frontend web y/o plugin de IDE.
- Grafo de llamadas (Java/C#) para seguir el flujo de un cálculo y mejorar el diagnóstico.
- Parsers para C# y SQL/PL-SQL (hoy se dividen por ventanas de líneas).
- Rellenado de los marcadores `[...]` de las plantillas con IA, por niveles (función → módulo → sistema).
- Protección anti-retroceso de reloj en la licencia y ofuscación del JAR.
