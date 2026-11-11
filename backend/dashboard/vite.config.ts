import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// The production build is served by Spring Boot, so it lands in the backend's static resources.
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
    chunkSizeWarningLimit: 900,
  },
  server: {
    port: 5173,
    // `npm run dev` talks to a backend running locally (for example with the demo profile).
    proxy: { '/api': 'http://localhost:8080' },
  },
});
