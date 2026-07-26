import {
    createContext,
    useContext,
} from 'react'

import usePomodoro from './usePomodoro'

const PomodoroContext =
    createContext(null)

export function PomodoroProvider({
    children,
}) {
    const pomodoro = usePomodoro()

    return (
        <PomodoroContext.Provider
            value={pomodoro}
        >
            {children}
        </PomodoroContext.Provider>
    )
}

export function usePomodoroContext() {
    const context =
        useContext(PomodoroContext)

    if (!context) {
        throw new Error(
            'usePomodoroContext debe usarse dentro de PomodoroProvider'
        )
    }

    return context
}