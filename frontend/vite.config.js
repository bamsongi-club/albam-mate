import { defineConfig, loadEnv } from 'vite';

export default defineConfig(({ mode }) => {
  const environment = loadEnv(mode, process.cwd(), '');
  return {
    test: {
      environment: 'jsdom'
    },
    server: {
      proxy: {
        '/api': {
          target: environment.VITE_API_PROXY_TARGET || 'http://localhost:8080',
          changeOrigin: true
        }
      }
    }
  };
});
