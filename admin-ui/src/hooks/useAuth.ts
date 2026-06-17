import { useState, useCallback } from 'react'

export function useAuth() {
  const [isLoggedIn, setIsLoggedIn] = useState(() => {
    return !!(localStorage.getItem('nyx_username') || localStorage.getItem('nyx_token'))
  })

  const login = useCallback((username: string, password: string) => {
    localStorage.setItem('nyx_username', username)
    localStorage.setItem('nyx_password', password)
    setIsLoggedIn(true)
  }, [])

  const loginWithToken = useCallback((token: string) => {
    localStorage.setItem('nyx_token', token)
    setIsLoggedIn(true)
  }, [])

  const logout = useCallback(() => {
    localStorage.removeItem('nyx_username')
    localStorage.removeItem('nyx_password')
    localStorage.removeItem('nyx_token')
    setIsLoggedIn(false)
  }, [])

  return { isLoggedIn, login, loginWithToken, logout }
}
