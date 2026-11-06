'use client'

import { forwardRef, useEffect, useImperativeHandle, useRef, useState } from 'react'

export const TURNSTILE_SITE_KEY = (process.env.NEXT_PUBLIC_TURNSTILE_SITE_KEY || '').trim()

export interface TurnstileHandle {
  reset: () => void
}

interface TurnstileWidgetProps {
  action: string
  onToken: (token: string) => void
  className?: string
}

declare global {
  interface Window {
    turnstile?: {
      render: (container: HTMLElement, options: Record<string, unknown>) => string
      reset: (widgetId?: string) => void
      remove: (widgetId?: string) => void
    }
  }
}

const SCRIPT_ID = 'cloudflare-turnstile-js'

function loadTurnstileScript() {
  if (typeof window === 'undefined') return Promise.resolve()
  if (window.turnstile) return Promise.resolve()
  const existing = document.getElementById(SCRIPT_ID) as HTMLScriptElement | null
  if (existing) {
    return new Promise<void>((resolve, reject) => {
      existing.addEventListener('load', () => resolve(), { once: true })
      existing.addEventListener('error', () => reject(new Error('Turnstile script failed')), { once: true })
    })
  }
  return new Promise<void>((resolve, reject) => {
    const script = document.createElement('script')
    script.id = SCRIPT_ID
    script.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit'
    script.async = true
    script.defer = true
    script.onload = () => resolve()
    script.onerror = () => reject(new Error('Turnstile script failed'))
    document.head.appendChild(script)
  })
}

export const TurnstileWidget = forwardRef<TurnstileHandle, TurnstileWidgetProps>(function TurnstileWidget({ action, onToken, className }, ref) {
  const containerRef = useRef<HTMLDivElement>(null)
  const widgetIdRef = useRef<string | undefined>(undefined)
  const onTokenRef = useRef(onToken)
  const [scriptFailed, setScriptFailed] = useState(false)

  useEffect(() => {
    onTokenRef.current = onToken
  }, [onToken])

  useImperativeHandle(ref, () => ({
    reset() {
      onTokenRef.current('')
      if (widgetIdRef.current && window.turnstile) {
        window.turnstile.reset(widgetIdRef.current)
      }
    }
  }), [])

  useEffect(() => {
    if (!TURNSTILE_SITE_KEY) {
      onTokenRef.current('')
      return
    }
    let cancelled = false
    loadTurnstileScript()
      .then(() => {
        if (cancelled || !containerRef.current || !window.turnstile || widgetIdRef.current) return
        widgetIdRef.current = window.turnstile.render(containerRef.current, {
          sitekey: TURNSTILE_SITE_KEY,
          action,
          callback: (token: string) => onTokenRef.current(token || ''),
          'expired-callback': () => onTokenRef.current(''),
          'error-callback': () => onTokenRef.current('')
        })
      })
      .catch(() => {
        if (!cancelled) setScriptFailed(true)
      })
    return () => {
      cancelled = true
      if (widgetIdRef.current && window.turnstile) {
        window.turnstile.remove(widgetIdRef.current)
        widgetIdRef.current = undefined
      }
    }
  }, [action])

  if (!TURNSTILE_SITE_KEY) {
    return null
  }

  return (
    <div className={className || 'turnstile-box'}>
      <div ref={containerRef} />
      {scriptFailed ? <small className="field-hint">人机验证加载失败，请刷新页面后重试</small> : null}
    </div>
  )
})
