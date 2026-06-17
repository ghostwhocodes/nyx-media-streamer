interface Props {
  activeTab: string
  onTabChange: (tab: string) => void
  onLogout: () => void
}

const tabs = [
  { id: 'dashboard', label: 'Dashboard', icon: '~' },
  { id: 'media', label: 'Media Roots', icon: '#' },
  { id: 'users', label: 'Users', icon: '@' },
  { id: 'transcode', label: 'Transcoding', icon: '>' },
  { id: 'settings', label: 'Settings', icon: '*' },
]

export default function Sidebar({ activeTab, onTabChange, onLogout }: Props) {
  return (
    <div className="w-56 bg-slate-800 min-h-screen flex flex-col">
      <div className="p-4 border-b border-slate-700">
        <h1 className="text-lg font-bold text-white">Nyx Admin</h1>
      </div>
      <nav className="flex-1 p-2">
        {tabs.map(tab => (
          <button
            key={tab.id}
            onClick={() => onTabChange(tab.id)}
            className={`w-full text-left px-4 py-2.5 rounded mb-1 text-sm font-medium transition-colors ${
              activeTab === tab.id
                ? 'bg-blue-600 text-white'
                : 'text-slate-300 hover:bg-slate-700'
            }`}
          >
            <span className="mr-2 font-mono">{tab.icon}</span>
            {tab.label}
          </button>
        ))}
      </nav>
      <div className="p-4 border-t border-slate-700">
        <button
          onClick={onLogout}
          className="w-full text-left px-4 py-2 rounded text-sm text-slate-400 hover:text-white hover:bg-slate-700"
        >
          Logout
        </button>
      </div>
    </div>
  )
}
