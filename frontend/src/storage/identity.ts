import { getDb, type Identity } from './db'

const KEY = 'current'

export async function saveIdentity(identity: Identity): Promise<void> {
  const db = await getDb()
  await db.put('identity', identity, KEY)
}

export async function loadIdentity(): Promise<Identity | undefined> {
  const db = await getDb()
  return db.get('identity', KEY)
}

export async function clearIdentity(): Promise<void> {
  const db = await getDb()
  await db.clear('identity')
}
