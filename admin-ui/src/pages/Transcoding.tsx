import { useState, useEffect } from 'react'
import { getTranscodeJobs, cancelJob, getConfig } from '../api/client'
import type { TranscodeJob, TranscodeInfo } from '../api/types'

export default function Transcoding() {
  const [jobs, setJobs] = useState<TranscodeJob[]>([])
  const [transcodeInfo, setTranscodeInfo] = useState<TranscodeInfo | null>(null)
  const [page, setPage] = useState(1)
  const [total, setTotal] = useState(0)
  const [error, setError] = useState('')

  const load = async () => {
    try {
      const [listing, config] = await Promise.all([getTranscodeJobs(page, 20), getConfig()])
      setJobs(listing.items)
      setTotal(listing.total)
      setTranscodeInfo(config.transcode)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load')
    }
  }

  useEffect(() => { load() }, [page])

  const handleCancel = async (jobId: string) => {
    try {
      await cancelJob(jobId)
      load()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to cancel')
    }
  }

  const statusColor = (status: string) => {
    switch (status) {
      case 'COMPLETED': return 'bg-green-600'
      case 'TRANSCODING':
      case 'PROBING': return 'bg-blue-600'
      case 'FAILED': return 'bg-red-600'
      case 'CANCELLED': return 'bg-yellow-600'
      default: return 'bg-slate-600'
    }
  }

  return (
    <div>
      <h2 className="text-xl font-bold mb-6">Transcoding</h2>

      {transcodeInfo && (
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-6">
          <div className="bg-slate-700 rounded p-3">
            <p className="text-xs text-slate-400">Format</p>
            <p className="font-medium">{transcodeInfo.defaultFormat}</p>
          </div>
          <div className="bg-slate-700 rounded p-3">
            <p className="text-xs text-slate-400">Max Concurrent Jobs</p>
            <p className="font-medium">{transcodeInfo.maxConcurrentJobs}</p>
          </div>
          <div className="bg-slate-700 rounded p-3">
            <p className="text-xs text-slate-400">Cache Grace Period</p>
            <p className="font-medium">{transcodeInfo.segmentCacheGracePeriodMinutes} min</p>
          </div>
        </div>
      )}

      <h3 className="text-lg font-semibold mb-3">Jobs ({total})</h3>
      {error && <p className="text-red-400 text-sm mb-4">{error}</p>}

      <div className="space-y-2 mb-4">
        {jobs.map(job => (
          <div key={job.id} className="bg-slate-700 rounded p-3 flex justify-between items-center">
            <div className="flex-1 min-w-0 mr-4">
              <p className="text-sm font-medium truncate">{job.inputPath.split('/').pop()}</p>
              <p className="text-xs text-slate-400">
                {job.profile} / {job.format} &mdash; {new Date(job.createdAt).toLocaleString()}
              </p>
            </div>
            <div className="flex items-center gap-2">
              <span className={`text-xs px-2 py-1 rounded ${statusColor(job.status)}`}>{job.status}</span>
              {(job.status === 'TRANSCODING' || job.status === 'PROBING' || job.status === 'QUEUED') && (
                <button
                  onClick={() => handleCancel(job.id)}
                  className="text-xs px-2 py-1 bg-red-600 hover:bg-red-700 rounded"
                >
                  Cancel
                </button>
              )}
            </div>
          </div>
        ))}
        {jobs.length === 0 && <p className="text-slate-400 text-sm">No transcode jobs.</p>}
      </div>

      {total > 20 && (
        <div className="flex gap-2">
          <button
            onClick={() => setPage(p => Math.max(1, p - 1))}
            disabled={page === 1}
            className="px-3 py-1 bg-slate-700 rounded text-sm disabled:opacity-50"
          >
            Prev
          </button>
          <span className="px-3 py-1 text-sm text-slate-400">Page {page}</span>
          <button
            onClick={() => setPage(p => p + 1)}
            disabled={page * 20 >= total}
            className="px-3 py-1 bg-slate-700 rounded text-sm disabled:opacity-50"
          >
            Next
          </button>
        </div>
      )}
    </div>
  )
}
