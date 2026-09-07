import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')

  return {
    plugins: [react()],
    optimizeDeps: {
      include: [
        '@fullcalendar/core',
        '@fullcalendar/daygrid',
        '@fullcalendar/interaction',
        '@fullcalendar/react',
        '@fullcalendar/timegrid',
      ],
    },
    test: {
      environment: 'jsdom',
      setupFiles: './src/test/setup.js',
    },
    server: {
      proxy: {
        '/api': {
          target: process.env.VITE_API_URL || env.VITE_API_URL || 'http://localhost:9000',
          rewrite: (path) => path.replace(/^\/api/, ''),
        },
      },
    },
  }
})
