import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // In dev the browser loads the app from this server rather than from Play, so a same-origin
    // call to /api would hit Vite. Forward those to Play instead. The prefix matches the /api
    // route conf/routes reserves; FrontendPlugin's frontendDevCommand documents the same contract.
    proxy: {
      '/api': 'http://localhost:9000',
    },
  },
})
