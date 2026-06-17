import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [
    react(),
    {
      name: 'strip-crossorigin-attributes',
      transformIndexHtml(html) {
        return html.replace(/\s+crossorigin(?:="")?/g, '')
      },
    },
  ],
  base: '/',
  server: {
    port: 3000,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
