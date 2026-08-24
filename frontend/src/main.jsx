import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { PomodoroProvider } from './pages/pomodoro/PomodoroContext'
import './index.css'
import App from './App.jsx'

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <PomodoroProvider>
      <App />
    </PomodoroProvider>
  </StrictMode>
)
