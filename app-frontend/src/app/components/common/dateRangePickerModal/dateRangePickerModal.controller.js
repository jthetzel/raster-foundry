/* globals _, document */
export default class DateRangePickerModalController {
    constructor($rootScope, $log, $scope, $timeout, moment, dateRangePickerConf) {
        'ngInject';
        $rootScope.autoInject(this, arguments);
        this.Moment = moment;
        this.dateRangePickerConf = dateRangePickerConf;
    }

    $onInit() {
        this.pickerApi = {};
        this.range = this.range || this.resolve.config.range || {};
        this._range = {
            start: this.range.start || this.Moment(),
            end: this.range.end || this.Moment()
        };
        this.ranges = this.resolve.config.ranges || [];
        this.minDay = this.resolve.config.minDay;
        this.maxDay = this.resolve.config.maxDay;

        this.$timeout(() => {
            this.inputElements = angular.element(
                document.getElementsByClassName('input-container')
            );
            this.startInput = $(this.inputElements[0].lastChild);
            this.endInput = $(this.inputElements[1].lastChild);
            this.setFormatTips();
            this.setStartEndValues();
            this.bindInputChangeEvents();
        }, 0);
    }

    setFormatTips() {
        $(this.inputElements[0]).append('<div class="format-tip">mm/dd/yyyy</div>');
        $(this.inputElements[1]).append('<div class="format-tip">mm/dd/yyyy</div>');
    }

    resetRange(moment, bound) {
        if (bound.length) {
            this._range[bound] = moment;
        }
    }

    checkInvalidFormat(isInvalid, bound) {
        if (bound === 'start') {
            this.isInvalidStartFormat = isInvalid;
        } else if (bound === 'end') {
            this.isInvalidEndFormat = isInvalid;
        }
    }

    resetDateDisplay(inputVal, inputEle, bound = '') {
        let date = {
            default: this.Moment(inputVal, 'MMM DD, YYYY', true),
            display: this.Moment(inputVal, 'MM/DD/YYYY', true)
        };
        if (date.default.isValid()) {
            this.resetRange(date.default, bound);
            this.$timeout(() => {
                inputEle.val(date.default.format('MM/DD/YYYY'));
            }, 0);
            this.checkInvalidFormat(false, bound);
        } else if (date.display.isValid()) {
            this.resetRange(date.display, bound);
            this.$timeout(() => {
                inputEle.val(date.display.format('MM/DD/YYYY'));
            }, 0);
            this.checkInvalidFormat(false, bound);
        } else {
            this.checkInvalidFormat(true, bound);
        }
    }

    setStartEndValues() {
        this.resetDateDisplay(this.startInput.val(), this.startInput, 'start');
        this.resetDateDisplay(this.endInput.val(), this.endInput, 'end');
    }

    isRangeValid() {
        return this._range.start.isBefore(this._range.end) ||
            this._range.start.isSame(this._range.end);
    }

    bindInputChangeEvents() {
        this.startInput.on('change', (e) => {
            this.resetDateDisplay(e.target.value, this.startInput, 'start');
        });
        this.endInput.on('change', (e) => {
            this.resetDateDisplay(e.target.value, this.endInput, 'end');
        });
    }

    onCalendarClick() {
        this.$timeout(() => {
            this.resetDateDisplay(this.startInput.val(), this.startInput, 'start');
            this.resetDateDisplay(this.endInput.val(), this.endInput, 'end');
        }, 0);
    }

    isActivePreset(range, index) {
        return this.selectedRangeIndex === index && this.matchesSelectedRange(range);
    }

    matchesSelectedRange(range) {
        if (_.isEmpty(range.start) || _.isEmpty(range.end)) {
            return true;
        }
        return range.start.isSame(this._range.start) && range.end.isSame(this._range.end);
    }

    onPresetSelect(range, index) {
        if (!_.isEmpty(range.start) && !_.isEmpty(range.end)) {
            this._range.start = range.start;
            this._range.end = range.end;
            this.isRangeEmpty = false;
        } else {
            this.isRangeEmpty = true;
        }
        this.selectedRangeIndex = index;
        this.$timeout(() => this.setStartEndValues(), 0);
    }

    getSelectedPreset() {
        if (this.selectedRangeIndex) {
            let selectedRange = this.ranges[this.selectedRangeIndex];
            if (this.matchesSelectedRange(selectedRange)) {
                return selectedRange;
            }
        }
        return false;
    }

    cancel() {
        this.closeWithData(false);
    }

    apply() {
        let data = {
            start: this.isRangeEmpty ? {} : this._range.start,
            end: this.isRangeEmpty ? {} : this._range.end
        };
        const selectedRange = this.getSelectedPreset();
        if (selectedRange) {
            data.preset = selectedRange.name;
        }
        this.closeWithData(data);
    }

    closeWithData(data) {
        this.close({$value: data});
    }
}
