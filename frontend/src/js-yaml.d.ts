declare module 'js-yaml' {
  export const FAILSAFE_SCHEMA: unknown
  export function load(input: string, options: {
    schema: unknown
    json: boolean
    maxDepth: number
    listener: (event: 'open' | 'close', state: { anchor: string | null }) => void
  }): unknown
}
