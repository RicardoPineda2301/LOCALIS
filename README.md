# LOCALIS

Asistente de IA **100% local** para comprender, documentar y diagnosticar código legacy. Corre en la máquina del cliente,
sin internet, sobre [Ollama](https://ollama.com). Backend en Java 21 (frontend pendiente).

## Qué hace

| Módulo | Endpoint | Descripción |
|---|---|---|
| Indexación | `POST /api/index`, `GET /api/index/status` | Indexa el repositorio del cliente de forma incremental (Java por método; SQL, .NET y otros por ventanas de líneas). Búsqueda híbrida BM25 + embeddings. |
| Explicar | `POST /api/explain` | `{"path": "src/.../Foo.java"}` y/o `{"question": "..."}`. Explica un fichero (por partes si es muy grande) o responde preguntas sobre el código. |
| Diagnosticar | `POST /api/diagnose` | `{"problem": "la facturación genera mal el total"}`. Devuelve hipótesis con evidencia `[fichero:línea]`. No corrige código. |
| Documentación | `GET /api/docs/templates`, `POST /api/docs/generate` | Genera el `.docx` de una plantilla con el logo, empresa, proyecto, versión y autor del cliente. |
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
  ollama pull codellama:13b-instruct
  ollama pull nomic-embed-text
  ```
  Los modelos se cambian en `localis.properties` (`ollama.chatModel`, `ollama.embedModel`). **Revise la licencia de cada
  modelo antes de distribuirlo comercialmente.**

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
