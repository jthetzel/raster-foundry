/* globals _ */

const {
    colorSchemes: defaultColorSchemes,
    colorSchemeTypes: defaultColorSchemeTypes,
    colorBlendModes: defaultColorBlendModes
} = require('./colorScheme.defaults.json');

export default (app) => {
    class ColorSchemeService {
        constructor() {
            this.defaultColorSchemes = defaultColorSchemes;
            this.defaultColorSchemeTypes = defaultColorSchemeTypes;
            this.defaultColorBlendModes = defaultColorBlendModes;
        }

        // (colors:string[], bitDepth:int) => { int: string }
        // colors are expected in css hex style (#FFFFFF)
        colorsToDiscreteScheme(colors, bitDepth = 8) {
            if (colors && colors.length > 1) {
                const spacing = Math.floor((Math.pow(2, bitDepth) - 1) / colors.length - 1);
                return colors.reduce((acc, color, index) => {
                    acc[index * spacing] = `${color}FF`;
                    return acc;
                }, {});
            }
            return false;
        }

        colorsToSequentialScheme(colors) {
            if (colors && colors.length > 1) {
                return colors.reduce((acc, color, index) => {
                    acc[index] = `${color}FF`;
                    return acc;
                }, {});
            }
            return false;
        }

        schemeFromBreaksAndColors(breaks, colors) {
            return breaks.reduce((acc, b, i) => {
                acc[b] = colors[i];
                return acc;
            }, {});
        }

        toBinnedColors(colors) {
            let numColors = colors.length;
            return _.zip(
                colors.map((color, index) => `${color} ${index / numColors * 100}%`),
                colors.map((color, index) => `${color} ${(index + 1) / numColors * 100}%`)
            ).join(', ');
        }

        // colors:string[] => { string: string }
        // colors are expected in css hex style (#FFFFFF)
        colorsToBackground(colors, direction = 90, bins = 0) {
            let colorString = bins > 0 ? this.toBinnedColors(colors) : colors.join(', ');
            const style = {
                background: `linear-gradient(${direction}deg, ${colorString})`
            };
            return style;
        }
    }

    app.service('colorSchemeService', ColorSchemeService);
};
