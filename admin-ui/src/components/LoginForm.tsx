import { useState } from 'react'
import { testConnection } from '../api/client'

interface Props {
  onLogin: (username: string, password: string) => void
  onTokenLogin: (token: string) => void
}

export default function LoginForm({ onLogin, onTokenLogin }: Props) {
  const [mode, setMode] = useState<'basic' | 'token'>('basic')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [token, setToken] = useState('')
  const [error, setError] = useState('')
  const [testing, setTesting] = useState(false)

  const handleBasicLogin = async () => {
    setError('')
    setTesting(true)
    const ok = await testConnection(username, password)
    setTesting(false)
    if (ok) {
      onLogin(username, password)
    } else {
      setError('Connection failed. Check credentials and server URL.')
    }
  }

  const handleTokenLogin = () => {
    if (token.trim()) {
      onTokenLogin(token.trim())
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-slate-900">
      <div className="bg-slate-800 p-8 rounded-lg shadow-xl w-96">
        <h1 className="text-2xl font-bold text-white mb-6">Nyx Media Server</h1>

        <div className="flex gap-2 mb-6">
          <button
            onClick={() => setMode('basic')}
            className={`flex-1 py-2 rounded text-sm font-medium ${mode === 'basic' ? 'bg-blue-600 text-white' : 'bg-slate-700 text-slate-300'}`}
          >
            Username/Password
          </button>
          <button
            onClick={() => setMode('token')}
            className={`flex-1 py-2 rounded text-sm font-medium ${mode === 'token' ? 'bg-blue-600 text-white' : 'bg-slate-700 text-slate-300'}`}
          >
            Bearer Token
          </button>
        </div>

        {mode === 'basic' ? (
          <div className="space-y-4">
            <input
              type="text"
              placeholder="Username"
              value={username}
              onChange={e => setUsername(e.target.value)}
              className="w-full p-3 bg-slate-700 rounded text-white placeholder-slate-400"
            />
            <input
              type="password"
              placeholder="Password"
              value={password}
              onChange={e => setPassword(e.target.value)}
              className="w-full p-3 bg-slate-700 rounded text-white placeholder-slate-400"
              onKeyDown={e => e.key === 'Enter' && handleBasicLogin()}
            />
            <button
              onClick={handleBasicLogin}
              disabled={testing || !username || !password}
              className="w-full py-3 bg-blue-600 hover:bg-blue-700 disabled:bg-slate-600 rounded text-white font-medium"
            >
              {testing ? 'Testing...' : 'Login'}
            </button>
          </div>
        ) : (
          <div className="space-y-4">
            <input
              type="password"
              placeholder="Bearer token"
              value={token}
              onChange={e => setToken(e.target.value)}
              className="w-full p-3 bg-slate-700 rounded text-white placeholder-slate-400"
              onKeyDown={e => e.key === 'Enter' && handleTokenLogin()}
            />
            <button
              onClick={handleTokenLogin}
              disabled={!token.trim()}
              className="w-full py-3 bg-blue-600 hover:bg-blue-700 disabled:bg-slate-600 rounded text-white font-medium"
            >
              Login
            </button>
          </div>
        )}

        {error && <p className="mt-4 text-red-400 text-sm">{error}</p>}
      </div>
    </div>
  )
}
