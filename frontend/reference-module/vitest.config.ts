import react from '@vitejs/plugin-react';
import { packageConfig } from '../vitest.shared.ts';

export default packageConfig({ plugins: [react()] });
