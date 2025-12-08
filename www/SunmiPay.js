var exec = require('cordova/exec');

var SunmiPay = {
    // Connect to the service
    connect: function(success, error) {
        exec(success, error, 'SunmiPay', 'connect', []);
    },

    // Example: Check for a card (Mag/IC/NFC)
    checkCard: function(success, error) {
        exec(success, error, 'SunmiPay', 'checkCard', []);
    },
    
    // Cancel card reading
    cancelCheckCard: function(success, error) {
        exec(success, error, 'SunmiPay', 'cancelCheckCard', []);
    }
};

module.exports = SunmiPay;