import { useState, useEffect } from 'react'
import { getConfig } from '../api/client'
import type { MediaRoot } from '../api/types'

export default function MediaRoots() {
  const [roots, setRoots] = useState<MediaRoot[]>([])
  const [error, setError] = useState('')

  useEffect(() => {
    getConfig()
      .then(c => setRoots(c.mediaRoots))
      .catch(e => setError(e instanceof Error ? e.message : 'Failed to load'))
  }, [])

  if (error) return <p className="text-red-400">{error}</p>

  return (
    <div>
      <h2 className="text-xl font-bold mb-6">Media Roots</h2>
      {roots.length === 0 ? (
        <p className="text-slate-400">No media roots configured.</p>
      ) : (
        <div className="space-y-2">
          {roots.map((root, i) => (
            <div key={i} className="bg-slate-700 rounded p-4 flex justify-between items-center">
              <div>
                <p className="font-medium">{root.path}</p>
                <p className="text-xs text-slate-400">{root.filesystem}</p>
              </div>
            </div>
          ))}
        </div>
      )}
      <p className="text-xs text-slate-500 mt-4">
        Media roots are configured in application.conf. Restart required after changes.
      </p>
    </div>
  )
}
