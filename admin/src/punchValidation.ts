const maxLocationAgeMs = 5 * 60 * 1000;
const futureToleranceMs = 2 * 60 * 1000;

export function validatePunchLocation(accuracy: number, timestamp: number) {
  if (!Number.isFinite(accuracy) || accuracy <= 0 || accuracy > 200)
    throw new Error('定位精度须大于0且不超过200米，请重新定位');
  const age = Date.now() - timestamp;
  if (!Number.isFinite(timestamp) || age > maxLocationAgeMs || age < -futureToleranceMs)
    throw new Error('定位采集时间须在5分钟内，且不得超过未来2分钟，请重新定位');
}
