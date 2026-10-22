// Flat key -> text maps, in the i18next JSON shape so a later move to react-i18next only touches the provider.
// Placeholders use {name}. `en` must have exactly the keys of `es` (checked by the compiler).
export const es = {
  'app.title': 'Control de Asistencia',
  'employeeHome.subtitle': 'Pantalla del empleado (en construcción)',
  'employeeHome.toAdmin': 'Ir al panel del administrador',
  'adminHome.title': 'Panel del administrador',
  'adminHome.subtitle': 'En construcción',
  'common.backHome': 'Volver al inicio',
  'notFound.title': 'Página no encontrada',
  'health.checking': 'Verificando servidor...',
  'health.online': 'Servidor en línea ({status}) - {time}',
  'health.unreachable': 'No se pudo contactar al servidor: {reason}',
  'health.unexpectedResponse': 'Respuesta inesperada del servidor',
  'health.httpStatus': 'HTTP {status}',
  'language.label': 'Idioma',
  'language.changed': 'Idioma: Español',
  // Endonyms: the same in every language, so each speaker can find their own.
  'language.name.es': 'Español',
  'language.name.en': 'English',
} as const

export type MessageKey = keyof typeof es

export const en: Record<MessageKey, string> = {
  'app.title': 'Attendance Control',
  'employeeHome.subtitle': 'Employee screen (under construction)',
  'employeeHome.toAdmin': 'Go to the admin panel',
  'adminHome.title': 'Admin panel',
  'adminHome.subtitle': 'Under construction',
  'common.backHome': 'Back to home',
  'notFound.title': 'Page not found',
  'health.checking': 'Checking server...',
  'health.online': 'Server online ({status}) - {time}',
  'health.unreachable': 'Could not reach the server: {reason}',
  'health.unexpectedResponse': 'Unexpected response from the server',
  'health.httpStatus': 'HTTP {status}',
  'language.label': 'Language',
  'language.changed': 'Language: English',
  'language.name.es': 'Español',
  'language.name.en': 'English',
}

export const resources = { es, en }
