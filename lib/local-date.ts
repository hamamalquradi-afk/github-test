export type LocalCalendarSource = Pick<Date, "getFullYear" | "getMonth" | "getDate">;

function twoDigits(value: number): string {
  return String(value).padStart(2, "0");
}

export function localToday(date: LocalCalendarSource = new Date()): string {
  return `${date.getFullYear()}-${twoDigits(date.getMonth() + 1)}-${twoDigits(date.getDate())}`;
}
