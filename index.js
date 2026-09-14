import {AppRegistry, Image} from 'react-native';
import {PluginManager} from 'sn-plugin-lib';
import App from './App';
import {name as appName} from './app.json';
import {BUTTON_ID_OPEN_LINKED, BUTTON_ID_SIDEBAR} from './src/domain/entryPoints';
// Installs the single PluginManager.registerButtonListener that fans button
// presses out to the screen (see src/infrastructure/pluginRouter.ts).
import {installPluginRouter} from './src/infrastructure/pluginRouter';
import {requestFileAccess} from './src/wiring';

const BUTTON_TYPE_SIDEBAR = 1;
const BUTTON_TYPE_LASSO = 2;
const SHOW_TYPE_WITH_UI = 1;
// regionType 3 = "Fullscreen display, won't dismiss" (NativePluginManager.d.ts).
// Canvas is a persistent full-screen canvas (spec/Canvas-PRD.md
// journey step 3), not a popup like Shapes/Mindmap.
const REGION_TYPE_FULLSCREEN_PERSISTENT = 3;
// Lasso-toolbar `editDataTypes` value for images (NativePluginManager.d.ts:
// 0=strokes, 1=title, 2=image, 3=text, 4=link), so "Open Canvas" only appears
// when the lasso holds a picture, such as a Canvas thumbnail.
const EDIT_DATA_TYPE_IMAGE = 2;

// File permissions (read notes; write and delete the canvas folder in MyStyle)
// are declared in PluginConfig.json and asked for as soon as the plugin loads,
// at install and at each start, as sn-shapes and sn-mindmap do. The session
// awaits the same request before it opens a canvas, so each dialog shows once
// (see src/infrastructure/filePermissions.ts).

AppRegistry.registerComponent(appName, () => App);

PluginManager.init();
installPluginRouter();
requestFileAccess();

const icon = Image.resolveAssetSource(require('./assets/icon.png')).uri;

PluginManager.registerButton(BUTTON_TYPE_SIDEBAR, ['NOTE'], {
  id: BUTTON_ID_SIDEBAR,
  name: 'Canvas',
  icon,
  showType: SHOW_TYPE_WITH_UI,
  regionType: REGION_TYPE_FULLSCREEN_PERSISTENT,
});

// FR13: lasso a Canvas thumbnail in the note, tap this, and its canvas reopens.
PluginManager.registerButton(BUTTON_TYPE_LASSO, ['NOTE'], {
  id: BUTTON_ID_OPEN_LINKED,
  name: 'Open Canvas',
  icon,
  showType: SHOW_TYPE_WITH_UI,
  editDataTypes: [EDIT_DATA_TYPE_IMAGE],
  regionType: REGION_TYPE_FULLSCREEN_PERSISTENT,
});
