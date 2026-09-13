module.exports = {
  preset: 'react-native',
  testPathIgnorePatterns: ['/node_modules/', '/__tests__/helpers/'],
  collectCoverageFrom: ['src/**/*.{ts,tsx}', 'App.tsx', 'index.js'],
  coveragePathIgnorePatterns: [
    '/node_modules/',
    '/__tests__/helpers/',
    '/build/',
    '/coverage/',
  ],
  // Commit gate — the same bar as sn-copilot: every metric at 97%+.
  coverageThreshold: {
    global: {
      statements: 97,
      branches: 97,
      functions: 97,
      lines: 97,
    },
  },
};
