import { useState, useEffect } from 'react'
import { getHealth, getTranscodeJobs } from '../api/client'
import type { HealthReport, TranscodeJob } from '../api/types'

export default function Dashboard() {
  const [health, setHealth] = useState<HealthReport | null>(null)
  const [activeJobs, setActiveJobs] = useState<TranscodeJob[]>([])
  const [error, setError] = useState('')

  useEffect(() => {
    const load = async () => {
      try {
        const [h, jobs] = await Promise.all([getHealth(), getTranscodeJobs(1, 5)])
        setHealth(h)
        setActiveJobs(jobs.items.filter(j => j.status === 'TRANSCODING' || j.status === 'PROBING'))
      } catch (e) {
        setError(e instanceof Error ? e.message : 'Failed to load')
      }
    }
    load()
    const interval = setInterval(load, 10000)
    return () => clearInterval(interval)
  }, [])

  if (error) return <p className="text-red-400">{error}</p>
  if (!health) return <p className="text-slate-400">Loading...</p>

  return (
    <div>
      <h2 className="text-xl font-bold mb-6">Dashboard</h2>
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-8">
        <Card label="Status" value={health.status} />
        <Card label="FFmpeg" value={health.ffmpegAvailable ? 'Available' : 'Unavailable'} />
        <Card label="Active Jobs" value={String(health.activeJobs)} />
      </div>

      <h3 className="text-lg font-semibold mb-3">Active Transcode Jobs</h3>
      {activeJobs.length === 0 ? (
        <p className="text-slate-400 text-sm">No active jobs</p>
      ) : (
        <div className="space-y-2">
          {activeJobs.map(job => (
            <div key={job.id} className="bg-slate-700 rounded p-3 flex justify-between items-center">
              <div>
                <p className="text-sm font-medium">{job.inputPath.split('/').pop()}</p>
                <p className="text-xs text-slate-400">{job.profile} / {job.format}</p>
              </div>
              <span className="text-xs px-2 py-1 bg-blue-600 rounded">{job.status}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

function Card({ label, value }: { label: string; value: string }) {
  return (
    <div className="bg-slate-700 rounded-lg p-4">
      <p className="text-xs text-slate-400 mb-1">{label}</p>
      <p className="text-lg font-semibold">{value}</p>
    </div>
  )
}
