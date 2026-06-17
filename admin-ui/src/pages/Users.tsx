import { useState, useEffect } from 'react'
import { getConfig, createUser, deleteUser } from '../api/client'

export default function Users() {
  const [users, setUsers] = useState<string[]>([])
  const [authEnabled, setAuthEnabled] = useState(false)
  const [newUsername, setNewUsername] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')

  const load = async () => {
    try {
      const c = await getConfig()
      setUsers(c.auth.users)
      setAuthEnabled(c.auth.enabled)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load')
    }
  }

  useEffect(() => { load() }, [])

  const handleCreate = async () => {
    setError('')
    setMessage('')
    try {
      await createUser(newUsername, newPassword)
      setMessage(`User "${newUsername}" created. Restart required to take effect.`)
      setNewUsername('')
      setNewPassword('')
      load()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to create user')
    }
  }

  const handleDelete = async (username: string) => {
    setError('')
    setMessage('')
    try {
      await deleteUser(username)
      setMessage(`User "${username}" removed. Restart required to take effect.`)
      load()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to delete user')
    }
  }

  return (
    <div>
      <h2 className="text-xl font-bold mb-6">Users</h2>

      <div className="bg-slate-700 rounded p-4 mb-6">
        <p className="text-sm">
          Auth: <span className={authEnabled ? 'text-green-400' : 'text-yellow-400'}>{authEnabled ? 'Enabled' : 'Disabled'}</span>
        </p>
      </div>

      <h3 className="text-lg font-semibold mb-3">Existing Users</h3>
      {users.length === 0 ? (
        <p className="text-slate-400 text-sm mb-6">No users configured.</p>
      ) : (
        <div className="space-y-2 mb-6">
          {users.map(u => (
            <div key={u} className="bg-slate-700 rounded p-3 flex justify-between items-center">
              <span className="font-medium">{u}</span>
              <button
                onClick={() => handleDelete(u)}
                className="text-xs px-3 py-1 bg-red-600 hover:bg-red-700 rounded text-white"
              >
                Remove
              </button>
            </div>
          ))}
        </div>
      )}

      <h3 className="text-lg font-semibold mb-3">Add User</h3>
      <div className="flex gap-2 mb-4">
        <input
          type="text"
          placeholder="Username"
          value={newUsername}
          onChange={e => setNewUsername(e.target.value)}
          className="flex-1 p-2 bg-slate-700 rounded text-white placeholder-slate-400 text-sm"
        />
        <input
          type="password"
          placeholder="Password (min 8 chars)"
          value={newPassword}
          onChange={e => setNewPassword(e.target.value)}
          className="flex-1 p-2 bg-slate-700 rounded text-white placeholder-slate-400 text-sm"
        />
        <button
          onClick={handleCreate}
          disabled={!newUsername || newPassword.length < 8}
          className="px-4 py-2 bg-blue-600 hover:bg-blue-700 disabled:bg-slate-600 rounded text-white text-sm font-medium"
        >
          Add
        </button>
      </div>

      {error && <p className="text-red-400 text-sm">{error}</p>}
      {message && <p className="text-green-400 text-sm">{message}</p>}
    </div>
  )
}
