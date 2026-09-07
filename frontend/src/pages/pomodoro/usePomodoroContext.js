import { useContext } from 'react'
import PomodoroContext from './pomodoroContextValue'

export default function usePomodoroContext() {
    const context = useContext(PomodoroContext)

    if (!context) {
        throw new Error(
            'usePomodoroContext debe usarse dentro de PomodoroProvider'
        )
    }

    return context
}