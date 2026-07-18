import { useEffect, useReducer, useState } from 'react'
import {
  MAX_BODY,
  deleteChirp,
  likeChirp,
  listChirps,
  postChirp,
  type Chirp,
} from './api'
import './App.css'

function timeAgo(iso: string): string {
  const seconds = Math.floor((Date.now() - Date.parse(iso)) / 1000)
  if (seconds < 60) return 'just now'
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}m`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}h`
  return new Date(iso).toLocaleDateString()
}

type ComposeProps = {
  handle: string
  onHandleChange: (handle: string) => void
  onPosted: () => void
}

function Compose({ handle, onHandleChange, onPosted }: ComposeProps) {
  const [body, setBody] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [posting, setPosting] = useState(false)

  // Spread counts code points, matching the server's rule — an emoji costs 1, not 2.
  const remaining = MAX_BODY - [...body].length
  const canPost = handle.trim() !== '' && body.trim() !== '' && remaining >= 0 && !posting

  async function post() {
    setPosting(true)
    setError(null)
    try {
      await postChirp(handle.trim(), body)
      setBody('')
      onPosted()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setPosting(false)
    }
  }

  return (
    <form
      className="compose"
      onSubmit={(e) => {
        e.preventDefault()
        if (canPost) void post()
      }}
    >
      <input
        className="handle"
        placeholder="your_handle"
        value={handle}
        maxLength={15}
        onChange={(e) => onHandleChange(e.target.value)}
        aria-label="Your handle"
      />
      <textarea
        placeholder="What's chirping?"
        value={body}
        rows={3}
        onChange={(e) => setBody(e.target.value)}
        aria-label="Chirp body"
      />
      <div className="compose-row">
        <span className={remaining < 0 ? 'remaining over' : 'remaining'}>{remaining}</span>
        <button type="submit" disabled={!canPost}>
          Chirp
        </button>
      </div>
      {error && <p className="error">{error}</p>}
    </form>
  )
}

type CardProps = {
  chirp: Chirp
  ownHandle: string
  onFilter: (author: string) => void
  onLike: (id: number) => void
  onDelete: (id: number) => void
}

function ChirpCard({ chirp, ownHandle, onFilter, onLike, onDelete }: CardProps) {
  return (
    <article className="chirp">
      <header>
        <button className="author" onClick={() => onFilter(chirp.author)}>
          @{chirp.author}
        </button>
        <time dateTime={chirp.createdAt}>{timeAgo(chirp.createdAt)}</time>
      </header>
      <p className="body">{chirp.body}</p>
      <footer>
        <button className="like" onClick={() => onLike(chirp.id)}>
          ♥ {chirp.likes}
        </button>
        {chirp.author === ownHandle.trim() && (
          <button className="delete" onClick={() => onDelete(chirp.id)}>
            delete
          </button>
        )}
      </footer>
    </article>
  )
}

export default function App() {
  const [chirps, setChirps] = useState<Chirp[]>([])
  const [filter, setFilter] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loaded, setLoaded] = useState(false)
  const [handle, setHandle] = useState(() => localStorage.getItem('chirper.handle') ?? '')

  useEffect(() => {
    localStorage.setItem('chirper.handle', handle)
  }, [handle])

  // Bumping `version` is how anything asks for a reload; the effect below owns the fetch.
  const [version, refresh] = useReducer((v: number) => v + 1, 0)

  useEffect(() => {
    // `cancelled` drops responses that arrive after the filter changed (or a newer refresh won),
    // so a slow request can never clobber a newer timeline.
    let cancelled = false
    listChirps(filter ?? undefined)
      .then((data) => {
        if (!cancelled) {
          setChirps(data)
          setError(null)
        }
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e))
      })
      .finally(() => {
        if (!cancelled) setLoaded(true)
      })
    return () => {
      cancelled = true
    }
  }, [filter, version])

  async function act(action: () => Promise<unknown>) {
    try {
      await action()
      refresh()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }

  return (
    <main className="app">
      <h1>Chirper</h1>
      <Compose handle={handle} onHandleChange={setHandle} onPosted={refresh} />
      {filter && (
        <p className="filter">
          Chirps by <strong>@{filter}</strong>{' '}
          <button onClick={() => setFilter(null)}>show all</button>
        </p>
      )}
      {error && <p className="error">{error}</p>}
      {loaded && chirps.length === 0 && !error && (
        <p className="empty">Nothing here yet. Be the first to chirp.</p>
      )}
      <section className="timeline" aria-label="Timeline">
        {chirps.map((chirp) => (
          <ChirpCard
            key={chirp.id}
            chirp={chirp}
            ownHandle={handle}
            onFilter={setFilter}
            onLike={(id) => void act(() => likeChirp(id))}
            onDelete={(id) => void act(() => deleteChirp(id))}
          />
        ))}
      </section>
    </main>
  )
}
