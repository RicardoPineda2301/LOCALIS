import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// En desarrollo, /api se reenvia al backend Java (npm run dev); en produccion se sirve desde el propio jar.
export default defineConfig({
  plugins: [react()],
  build: { outDir: '../src/main/resources/web', emptyOutDir: true },
  server: { proxy: { '/api': 'http://127.0.0.1:8080' } },
})
