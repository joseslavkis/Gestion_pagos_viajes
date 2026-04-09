cat >/usr/share/nginx/html/env-config.js <<EOF
window._env_ = {
  baseApiUrl: "${BACKEND_EXTERNAL_URL:-}"
};
EOF

exec nginx -g 'daemon off;'
