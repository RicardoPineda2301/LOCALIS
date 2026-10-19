# Guia para usar LOCALIS en tu equipo

LOCALIS es un asistente de inteligencia artificial que funciona **completamente offline** (sin necesidad de internet). Analiza tu codigo fuente, responde preguntas sobre el, genera documentacion y ayuda a encontrar errores.

## Requisitos previos

Para usar LOCALIS necesitas:

1. **Ollama** - El programa que ejecuta el modelo de IA en tu computadora (como un "motor")
2. **Modelos de IA** - Los archivos que contiene el conocimiento (como el "cerebro" del asistente)
3. **Java** - El lenguaje en el que esta escrito LOCALIS

Si no tienes Ollama instalado, el instalador de LOCALIS lo puede instalar automaticamente.

---

## Paso 1: Iniciar Ollama

Ollama suele iniciar automaticamente con Windows. Para verificar si esta funcionando:

```powershell
& "$env:LOCALAPPDATA\Programs\Ollama\ollama.exe" list
```

Si no recibes una respuesta o ves un error, inicia Ollama manualmente:

```powershell
Start-Process "$env:LOCALAPPDATA\Programs\Ollama\ollama app.exe"
```

### Descargar los modelos de IA (solo la primera vez)

Ejecuta estos comandos para descargar los modelos necesarios:

```powershell
ollama pull mistralai/Devstral-Small-2-24B-Instruct-2512
ollama pull nomic-embed-text
```

**Nota importante sobre el modelo:**
- El modelo principal (`Devstral-Small-2-24B-Instruct-2512`) necesita aproximadamente **48 GB de memoria RAM**
- Si tu computadora tiene menos memoria, necesitaras usar la configuracion de CPU offloading
- Para computadoras con GPU de 8 GB: ejecuta Ollama con `OLLAMA_FLASH_ATTENTION=0` antes de empezar

---

## Paso 2: Configurar LOCALIS

Antes de ejecutar LOCALIS por primera vez:

1. Copia el archivo `localis.properties.example` y renombralo a `localis.properties`
2. Abre el archivo `localis.properties` con un editor de texto
3. Asegurate de que tenga esta linea (ya deberia estar):
   ```
   ollama.chatModel=mistralai/Devstral-Small-2-24B-Instruct-2512
   ```
4. Si tu proyecto tiene carpetas que no son codigo (como imagenes, documentos, etc.), agrégalas a `index.ignoreDirs` separadas por comas

---

## Paso 3: Ejecutar LOCALIS

Desde la carpeta donde esta LOCALIS (la carpeta del proyecto), ejecuta:

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-25.0.4.101-hotspot"
$env:Path = "$env:JAVA_HOME\bin;$env:USERPROFILE\.maven\apache-maven-3.9.11\bin;$env:Path"

# Solo si modificaste el codigo de LOCALIS:
mvn -B -q clean package -DskipTests

# Iniciar LOCALIS:
java --enable-native-access=ALL-UNNAMED -jar target\localis.jar localis.properties
```

Una vez que LOCALIS este funcionando:
- Abre tu navegador web
- Ve a la direccion: http://127.0.0.1:8080
- Veras la interfaz de LOCALIS
- Haz clic en el boton **Configurar** para seleccionar el proyecto de codigo que quieres analizar

---

## Consejos antes de hacer una demostracion

- **Prueba antes**: Indexa tu proyecto y haz una pregunta de prueba con anticipacion. Esto carga el modelo en memoria y evita que la primera pregunta de la demostracion sea lenta.

- **No cambies de proyecto durante el analisis**: Si LOCALIS esta analizando un proyecto y decides cambiar a otro, espera a que termine el analisis actual o cancelalo primero.

- **Mantén Ollama funcionando**: Si reinicias tu computadora o cierras Ollama, necesitaras volver a cargar el modelo.

- **Si tienes problemas con la GPU**: Si ves errores relacionados con CUDA o la GPU, puedes ejecutar Ollama con CPU offloading usando este comando antes de iniciar LOCALIS:
  ```powershell
  $env:OLLAMA_FLASH_ATTENTION = 0
  ```

---

## Como verificar que todo funciona correctamente

Puedes comprobar el estado del sistema con estos comandos:

1. Estado de LOCALIS y Ollama:
```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/health
```

2. Ver que modelos de IA estan cargados en Ollama:
```powershell
Invoke-RestMethod http://localhost:11434/api/ps
```

---

## Resumen de lo que hace LOCALIS

- **Indexa tu codigo**: Crea un indice de busca de todo tu proyecto para poder encontrar informacion rapidamente
- **Responde preguntas**: Puedes preguntarle sobre como funciona cieto codigo, donde esta una funcionalidad, etc.
- **Documenta**: Genera documentacion automatica basada en plantillas
- **Analiza**: Ayuda a encontrar errores y sugiere optimizaciones

Todo esto funciona **sin internet**, usando unicamente los recursos de tu computadora.
