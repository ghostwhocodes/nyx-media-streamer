import type { SanitizedConfig, ConfigUpdateResponse, HealthReport, TranscodeJob, TranscodeJobListing } from './types'

interface RawTranscodeJob {
  id: string
  status: string
  input_path: string
  profile: string
  representation: string
  created_at: string | null
  updated_at: string | null
}

interface RawTranscodeJobListing {
  jobs: RawTranscodeJob[]
  total: number
  page: number
  limit: number
}

function getAuthHeader(): Record<string, string> {
  const username = localStorage.getItem('nyx_username')
  const password = localStorage.getItem('nyx_password')
  const token = localStorage.getItem('nyx_token')

  if (token) {
    return { Authorization: `Bearer ${token}` }
  }
  if (username && password) {
    return { Authorization: `Basic ${btoa(`${username}:${password}`)}` }
  }
  return {}
}

async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...getAuthHeader(),
      ...init?.headers,
    },
  })
  if (!res.ok) {
    const body = await res.text()
    throw new Error(`${res.status}: ${body}`)
  }
  if (res.status === 204) return undefined as T
  return res.json()
}

export async function getHealth(): Promise<HealthReport> {
  return apiFetch('/api/v1/health')
}

export async function getConfig(): Promise<SanitizedConfig> {
  return apiFetch('/api/v1/config')
}

export async function updateConfig(updates: Record<string, unknown>): Promise<ConfigUpdateResponse> {
  return apiFetch('/api/v1/config', {
    method: 'PUT',
    body: JSON.stringify(updates),
  })
}

export async function createUser(username: string, password: string): Promise<{ username: string }> {
  return apiFetch('/api/v1/auth/users', {
    method: 'POST',
    body: JSON.stringify({ username, password }),
  })
}

export async function deleteUser(username: string): Promise<void> {
  return apiFetch(`/api/v1/auth/users/${encodeURIComponent(username)}`, {
    method: 'DELETE',
  })
}

export async function getTranscodeJobs(page = 1, limit = 20): Promise<TranscodeJobListing> {
  const listing = await apiFetch<RawTranscodeJobListing>(`/api/v1/transcode/jobs?page=${page}&limit=${limit}`)
  return {
    items: listing.jobs.map(toTranscodeJob),
    page: listing.page,
    limit: listing.limit,
    total: listing.total,
  }
}

function toTranscodeJob(job: RawTranscodeJob): TranscodeJob {
  return {
    id: job.id,
    status: job.status,
    inputPath: job.input_path,
    profile: job.profile,
    format: job.representation,
    createdAt: job.created_at ?? '',
    updatedAt: job.updated_at ?? '',
  }
}

export async function cancelJob(jobId: string): Promise<void> {
  return apiFetch(`/api/v1/transcode/jobs/${jobId}`, { method: 'DELETE' })
}

export async function testConnection(username: string, password: string): Promise<boolean> {
  try {
    const res = await fetch('/api/v1/health', {
      headers: { Authorization: `Basic ${btoa(`${username}:${password}`)}` },
    })
    return res.ok
  } catch {
    return false
  }
}
