/** Query keys of the student screens. After ANY write that changes a student, a cycle or a class, all of these are refreshed. */
export const BOARD_KEY = ['coach', 'board'] as const;
export const PROFILE_KEYS = ['coach', 'profile'] as const;
export const profileKey = (studentId: string) => ['coach', 'profile', studentId] as const;
export const PLANS_KEY = ['coach', 'plans'] as const;
export const sheetKey = (studentId: string) => ['coach', 'sheet', studentId] as const;
