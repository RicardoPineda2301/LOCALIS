# Instalacion de LOCALIS para clientes

Este documento explica como instalar LOCALIS en la computadora de un cliente usando el instalador.

---

## Que necesitas

Para instalar LOCALIS, necesitas:

1. **Windows 10 o superior** (LOCALIS esta diseñado para Windows)
2. **Conexion a internet** (solo para la instalacion inicial, despues funciona offline)
3. **Espacio en disco**: 
   - Aplicacion LOCALIS: ~200 MB
   - Java Runtime: ~200 MB
   - Ollama: ~300 MB
   - **Modelos de IA: ~50 GB** (solo la primera vez)

4. **Memoria RAM minima**: 16 GB (recomendado 32 GB o mas para el modelo de Mistral)

---

## Pasos de instalacion

### Opcion A: Instalacion automatica con descarga de modelos (recomendado)

Esta opcion descarga automaticamente los modelos de IA durante la instalacion.

1. Abre PowerShell como **administrador**
2. Navega a la carpeta donde esta el instalador
3. Ejecuta:
   ```powershell
   .\install.ps1 -Scope Machine -DownloadModels
   ```

El instalador:
- Instalara Java si no esta presente
- Instalara Ollama (el motor de IA)
- Descargara los modelos necesarios desde internet
- Configurara LOCALIS
- Creara el comando `localis` para ejecutarlo facilmente

**Duracion estimada**: 10-60 minutos (dependiendo de la velocidad de internet y del hardware)

---

### Opcion B: Instalacion basica (sin modelos)

Si no quieres descargar los modelos durante la instalacion o no tienes buena conexion a internet:

```powershell
.\install.ps1 -Scope Machine
```

Luego, manualmente descarga los modelos:
```powershell
ollama pull mistralai/Devstral-Small-2-24B-Instruct-2512
ollama pull nomic-embed-text
```

---

### Opcion C: Instalacion para usuario actual (sin permisos de administrador)

Si no tienes permisos de administrador, puedes instalar solo para tu usuario:

```powershell
.\install.ps1 -Scope User -DownloadModels
```

---

## despues de la instalacion

1. **Reinicia tu computadora** (para que se apliquen los cambios de PATH y JAVA_HOME)

2. **Abre una nueva terminal** (PowerShell o CMD)

3. **Verifica que todo esta instalado**:
   ```powershell
   localis --help
   ```

4. **Configura tu proyecto**:
   - Ve a la carpeta donde esta el instalador
   - Copia `localis.properties.example` a `localis.properties`
   - Edita `localis.properties` y configura:
     - `repo.path`: la ruta a tu proyecto de codigo (ej: `C:\mi-proyecto`)
     - `branding.company`: el nombre de tu empresa
     - `branding.logo`: la ruta a tu logo (opcional)

5. **Inicia LOCALIS**:
   ```powershell
   localis
   ```

6. **Abre tu navegador** y ve a: http://127.0.0.1:8080

7. **Selecciona tu proyecto** usando el boton **Configurar** en la interfaz web

---

## Requisitos de hardware para los modelos

El modelo principal (`Devstral-Small-2-24B-Instruct-2512`) tiene estos requisitos:

| Requisito | Minimo | Recomendado |
|-----------|--------|-------------|
| **RAM** | 16 GB | 48 GB o mas |
| **GPU** | Opcional | 24 GB VRAM |
| **Disco** | 50 GB libres | 100 GB libres |

### Si tienes poca memoria RAM:

El modelo puede funcionar con menos RAM usando **CPU offloading**. Para ello:

1. Abre PowerShell
2. Ejecuta antes de iniciar LOCALIS:
   ```powershell
   $env:OLLAMA_FLASH_ATTENTION = 0
   ollama app
   ```
3. En otra ventana, inicia LOCALIS:
   ```powershell
   localis
   ```

Esto hara que el modelo use tanto la GPU como la CPU, reduciendo los requisitos de VRAM.

---

## Solucion de problemas

### Problema: Ollama no esta instalado

Si ves un error sobre Ollama, puedes instalarlo manualmente:
1. Descarga Ollama desde: https://ollama.com
2. Ejecuta el instalador
3. Reinicia tu computadora
4. Descarga los modelos:
   ```powershell
   ollama pull mistralai/Devstral-Small-2-24B-Instruct-2512
   ollama pull nomic-embed-text
   ```

### Problema: No hay suficiente memoria

Si tu computadora no tiene suficiente RAM para el modelo:
- Usa CPU offloading como se explica arriba
- Considera usar un modelo mas pequeño (contacta al soporte)

### Problema: Error de puerto ocupado

Si ves un error sobre el puerto 8080:
- Asegurate de que no haya otra instancia de LOCALIS funcionando
- Cierra cualquier terminal donde LOCALIS este en ejecucion
- Espera unos minutos y vuelve a intentar

### Problema: El modelo no esta descargado

Si LOCALIS no encuentra el modelo, verifica:
1. Ollama esta funcionando: `ollama list`
2. El modelo esta descargado: `ollama pull mistralai/Devstral-Small-2-24B-Instruct-2512`

---

## Desinstalacion

Para desinstalar LOCALIS:

1. Abre PowerShell como administrador
2. Ejecuta:
   ```powershell
   .\uninstall.ps1
   ```

Esto eliminara LOCALIS pero **no eliminara Ollama ni los modelos descargados**.

---

## Soporte

Si tienes problemas durante la instalacion:

1. Verifica que tienes suficiente espacio en disco y memoria RAM
2. Revisa que tu conexion a internet este funcionando
3. Asegurate de estar ejecutando PowerShell como administrador
4. Consulta el archivo `install.json` en la carpeta de instalacion para ver detalles de la instalacion

---

## Notas importantes

- LOCALIS **funciona completamente offline** despues de la instalacion
- Solo necesita internet la primera vez para descargar Ollama y los modelos
- Los modelos de IA se guardan en tu computadora y no se envian a ningun servidor
- TODOS los datos de tu codigo se procesan localmente, sin salir de tu computadora
