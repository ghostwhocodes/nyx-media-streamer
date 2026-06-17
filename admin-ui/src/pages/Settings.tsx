import { useState, useEffect } from 'react'
import { getConfig, updateConfig } from '../api/client'

export default function Settings() {
  const [host, setHost] = useState('')
  const [port, setPort] = useState(8080)
  const [corsOrigins, setCorsOrigins] = useState('')
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    getConfig()
      .then(c => {
        setHost(c.host)
        setPort(c.port)
        setCorsOrigins(c.corsOrigins.join(', '))
        setLoading(false)
      })
      .catch(e => {
        setError(e instanceof Error ? e.message : 'Failed to load')
        setLoading(false)
      })
  }, [])

  const handleSave = async () => {
    setError('')
    setMessage('')
    try {
      const result = await updateConfig({
        host,
        port,
        corsOrigins: corsOrigins.split(',').map(s => s.trim()).filter(Boolean),
      })
      if (result.restartRequired) {
        setMessage('Settings saved. Server restart required for changes to take effect.')
      } else {
        setMessage('Settings saved.')
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to save')
    }
  }

  if (loading) return <p className="text-slate-400">Loading...</p>

  return (
    <div>
      <h2 className="text-xl font-bold mb-6">Settings</h2>

      <div className="space-y-4 max-w-lg">
        <div>
          <label className="block text-sm text-slate-400 mb-1">Bind Address</label>
          <input
            type="text"
            value={host}
            onChange={e => setHost(e.target.value)}
            className="w-full p-2 bg-slate-700 rounded text-white text-sm"
          />
        </div>

        <div>
          <label className="block text-sm text-slate-400 mb-1">Port</label>
          <input
            type="number"
            value={port}
            onChange={e => setPort(Number(e.target.value))}
            className="w-full p-2 bg-slate-700 rounded text-white text-sm"
          />
        </div>

        <div>
          <label className="block text-sm text-slate-400 mb-1">CORS Origins (comma-separated)</label>
          <input
            type="text"
            value={corsOrigins}
            onChange={e => setCorsOrigins(e.target.value)}
            className="w-full p-2 bg-slate-700 rounded text-white text-sm"
            placeholder="* or http://example.com, http://other.com"
          />
        </div>

        <button
          onClick={handleSave}
          className="px-6 py-2 bg-blue-600 hover:bg-blue-700 rounded text-white font-medium text-sm"
        >
          Save
        </button>
      </div>

      {error && <p className="mt-4 text-red-400 text-sm">{error}</p>}
      {message && <p className="mt-4 text-green-400 text-sm">{message}</p>}
    </div>
  )
}
