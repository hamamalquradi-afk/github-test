export type SearchParamsReader = { get(name: string): string | null };

export function customerSearchQuery(searchParams: SearchParamsReader): string {
  return (searchParams.get("q") ?? "").trim();
}

export type LatestSearchResult<T> =
  | { current: true; value: T; error?: never }
  | { current: true; value?: never; error: unknown }
  | { current: false; value?: T; error?: unknown };

export function createLatestSearchRunner<T>(load: (query: string) => Promise<T>) {
  let sequence = 0;

  return async (query: string): Promise<LatestSearchResult<T>> => {
    const requestId = ++sequence;
    try {
      const value = await load(query);
      return { current: requestId === sequence, value };
    } catch (error) {
      return { current: requestId === sequence, error };
    }
  };
}
