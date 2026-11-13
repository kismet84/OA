import { ref } from 'vue';
export const uploadStatus = ref('');
/** JPEG and PNG only; flatten transparency on white before JPEG encoding. */
export async function compressAttachment(file: File): Promise<File> {
  if (!['image/jpeg', 'image/png'].includes(file.type)) return file;
  // Read dimensions without re-encoding small PNGs. This also preserves existing tiny
  // PNG attachments that browser bitmap decoders handle more strictly than viewers.
  if(file.type==='image/png'&&file.size<=1.5*1024*1024){
    const header=new DataView(await file.slice(0,24).arrayBuffer());
    if(header.byteLength>=24&&header.getUint32(0)===0x89504e47){const width=header.getUint32(16),height=header.getUint32(20);if(width>0&&height>0&&Math.max(width,height)<=2560)return file;}
  }
  const bitmap = await createImageBitmap(file).catch(() => { throw new Error('图片读取失败，请重新选择有效的 JPEG 或 PNG'); });
  try {
    const longest = Math.max(bitmap.width, bitmap.height);
    if (file.size <= 1.5 * 1024 * 1024 && longest <= 2560) return file;
    const scale = Math.min(1, 2560 / longest), canvas = document.createElement('canvas');
    canvas.width = Math.max(1, Math.round(bitmap.width * scale));
    canvas.height = Math.max(1, Math.round(bitmap.height * scale));
    const ctx = canvas.getContext('2d');
    if (!ctx) throw new Error('浏览器无法压缩图片，请更新浏览器后重试');
    ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    const encode = (quality: number) => new Promise<Blob>((resolve, reject) => canvas.toBlob(b => b ? resolve(b) : reject(new Error('图片压缩失败')), 'image/jpeg', quality));
    let blob = await encode(0.82);
    for (const quality of [0.74, 0.66, 0.60]) { if (blob.size <= 2 * 1024 * 1024) break; blob = await encode(quality); }
    return blob.size < file.size ? new File([blob], file.name.replace(/\.[^.]+$/, '') + '.jpg', { type: 'image/jpeg' }) : file;
  } finally { bitmap.close(); }
}
export async function prepareAttachment(body: FormData) {
  const file = body.get('file');
  if (!(file instanceof File)) return;
  const index = body.get('uploadIndex') || '1', total = body.get('uploadTotal') || '1';
  uploadStatus.value = `正在压缩 第 ${index}/${total} 份`;
  try {
    const prepared = await compressAttachment(file);
    if (!prepared.size || prepared.size > 10 * 1024 * 1024) throw new Error('每份最多 10MB');
    body.set('file', prepared); uploadStatus.value = `正在上传 第 ${index}/${total} 份`;
  } catch(e) { uploadStatus.value = ''; throw e; }
}
