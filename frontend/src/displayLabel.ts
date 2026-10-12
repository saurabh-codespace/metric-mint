// Presentation only: never use these labels as connection or query identifiers.
export function displayLabel(value: string): string {
  return value.replace(/\bToscana(?:[-_]I2P-SIT|[-_]IFRS)?\b/gi, 'Connected source')
    .replace(/\s*\/\s*I2P\b/gi, '')
    .replace(/\bI2P invoice\b/gi, 'invoice')
    .replace(/\bI2P\b/gi, 'invoice processing')
    .replace(/\bIFRS\b/gi, 'financial reporting');
}
