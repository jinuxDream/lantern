'use strict';

angular.module('dashboard', ['dashboard.services']).
    config(['$routeProvider', function($routeProvider) {
    $routeProvider.
        when('/status', {templateUrl: 'partials/status.html',   controller: StatusCtrl}).
        when('/settings', {templateUrl: 'partials/settings.html',   controller: SettingsCtrl}).
        when('/roster', {templateUrl: 'partials/roster.html', controller: RosterCtrl}).
        otherwise({redirectTo: '/status'});
}]);