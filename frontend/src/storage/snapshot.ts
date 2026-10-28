import { getDb, type Snapshot } from './db'

const KEY = 'current'

export async function saveSnapshot(snapshot: Snapshot): Promise<void> {
  const db = await getDb()
  await db.put('snapshot', snapshot, KEY)
}

export async function loadSnapshot(): Promise<Snapshot | undefined> {
  const db = await getDb()
  return db.get('snapshot', KEY)
}

export async function clearSnapshot(): Promise<void> {
  const db = await getDb()
  await db.clear('snapshot')
}
