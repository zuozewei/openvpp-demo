import { reactive } from 'vue'

const STORAGE_KEY = 'openvpp_admin_session'

function loadSession() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

const state = reactive({
  session: loadSession()
})

export function getSession() {
  return state.session
}

export function setSession(session) {
  state.session = session
  localStorage.setItem(STORAGE_KEY, JSON.stringify(session))
}

export function clearSession() {
  state.session = null
  localStorage.removeItem(STORAGE_KEY)
}
