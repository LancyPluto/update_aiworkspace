import { http } from './http'

export function register(body) {
  return http.post('/auth/register', body).then((r) => r.data)
}

export function login(body) {
  return http.post('/auth/login', body).then((r) => r.data)
}

export function me() {
  return http.get('/users/me').then((r) => r.data)
}
