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
          // 서버가 접속 주소와 같은 소셜 callback URI를 계산하도록 브라우저가 보낸 Host를 그대로 넘긴다.
          changeOrigin: false,
          ws: true
        },
        // 프로필 이미지 등 업로드 파일. nginx.local.conf의 /uploads/ 프록시와 대상을 맞춘다.
        '/uploads': {
          target: environment.VITE_API_PROXY_TARGET || 'http://localhost:8080',
          changeOrigin: false
        }
      }
    }
  };
});
