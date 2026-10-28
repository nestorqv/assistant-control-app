import { openFrontCamera, type OpenCamera } from './camera'
import { compressImage, type Compressed } from './compressImage'
import { acquireLocation, queryPermission, type LocationController, type PermissionKind } from './geolocation'

type OpenCameraFn = () => Promise<OpenCamera>
type CompressFn = (source: HTMLVideoElement | Blob) => Promise<Compressed>
type PermissionFn = (name: 'camera' | 'geolocation') => Promise<PermissionKind>

/** Everything SelfieCapture needs from the browser, injectable so tests can fake it. */
export type CaptureDeps = {
  openCamera: OpenCameraFn
  acquire: () => LocationController
  compress: CompressFn
  permission: PermissionFn
}

export const realCaptureDeps: CaptureDeps = {
  openCamera: () => openFrontCamera(),
  acquire: () => acquireLocation(),
  compress: (source) => compressImage(source),
  permission: queryPermission,
}
