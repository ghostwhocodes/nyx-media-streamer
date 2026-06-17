import { useState } from 'react'
import { useAuth } from './hooks/useAuth'
import LoginForm from './components/LoginForm'
import Sidebar from './components/Sidebar'
import Dashboard from './pages/Dashboard'
import MediaRoots from './pages/MediaRoots'
import Users from './pages/Users'
import Transcoding from './pages/Transcoding'
import Settings from './pages/Settings'

export default function App() {
  const { isLoggedIn, login, loginWithToken, logout } = useAuth()
  const [activeTab, setActiveTab] = useState('dashboard')

  if (!isLoggedIn) {
    return <LoginForm onLogin={login} onTokenLogin={loginWithToken} />
  }

  const renderPage = () => {
    switch (activeTab) {
      case 'dashboard': return <Dashboard />
      case 'media': return <MediaRoots />
      case 'users': return <Users />
      case 'transcode': return <Transcoding />
      case 'settings': return <Settings />
      default: return <Dashboard />
    }
  }

  return (
    <div className="flex min-h-screen bg-slate-900 text-slate-200">
      <Sidebar activeTab={activeTab} onTabChange={setActiveTab} onLogout={logout} />
      <main className="flex-1 p-8">
        {renderPage()}
      </main>
    </div>
  )
}
