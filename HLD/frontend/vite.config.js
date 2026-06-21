import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The dev server proxies API calls to the Spring Boot backend on :8080, so the
// browser talks to a single origin (no CORS needed) and the exact backend route
// names (/suggest, /search, ...) are preserved.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/suggest': 'http://localhost:8080',
      '/search': 'http://localhost:8080',
      '/trending': 'http://localhost:8080',
      '/cache': 'http://localhost:8080',
      '/stats': 'http://localhost:8080',
      '/admin': 'http://localhost:8080',
    },
  },
});
