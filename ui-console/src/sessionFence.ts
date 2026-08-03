export interface SessionLease {
  token: string
  generation: number
}

/**
 * Tracks the authenticated console identity. A response may update state only
 * while the lease captured when its request started still belongs to the
 * active session.
 */
export class SessionFence {
  private generation = 0

  begin(token: string): SessionLease | undefined {
    if (!token) {
      return undefined
    }
    return { token, generation: this.generation }
  }

  activate(): void {
    this.generation += 1
  }

  invalidate(): void {
    this.generation += 1
  }

  isCurrent(lease: SessionLease, token: string): boolean {
    return Boolean(token)
      && lease.token === token
      && lease.generation === this.generation
  }
}
