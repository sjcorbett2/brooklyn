/**
 * Render entity sensors tab.
 *
 * @type {*}
 */
define([
    "underscore", "jquery", "backbone", "brooklyn-utils",
    "view/viewutils", "model/sensor-summary", "text!tpl/apps/sensors.html", 
    "jquery-datatables", "datatables-extensions"
], function (_, $, Backbone, Util, ViewUtils, SensorSummary, SensorsHtml) {

    var EntitySensorsView = Backbone.View.extend({
        template:_.template(SensorsHtml),
        sensorMetadata:{},
        refreshActive:true,
        events:{
            'click .refresh':'refreshSensors',
            'click .filterEmpty':'toggleFilterEmpty',
            'click .toggleAutoRefresh':'toggleAutoRefresh'
        },
        initialize:function () {
            this.$el.html(this.template({ }));
            $.ajaxSetup({ async:false });
            var that = this,
                $table = this.$('#sensors-table');
            that.table = ViewUtils.myDataTable($table, {
                "fnRowCallback": function( nRow, aData, iDisplayIndex, iDisplayIndexFull ) {
                    $(nRow).attr('id', aData[0])
                    $('td',nRow).each(function(i,v){
                        if (i==1) $(v).attr('class','sensor-actions');
                        if (i==2) $(v).attr('class','sensor-value');
                    })
                    return nRow;
                },
                "aoColumnDefs": [
                                 { // name (with tooltip)
                                     "mRender": function ( data, type, row ) {
                                         // name (column 1) should have tooltip title
                                         return '<span class="sensor-name" rel="tooltip" title="<b>'+
                                             Util.prep(data['description'])+'</b><br/>('+
                                             Util.prep(data['type'])+')" data-placement="left">'+
                                             Util.prep(data['name'])+'</span>';
                                     },
                                     "aTargets": [ 1 ]
                                 },
                                 { // actions
                                     "mRender": function ( actions, type, row ) {
                                         var actionsText = ""
                                         _.each(actions, function(v,k) {
                                             var text=k
                                             var icon=""
                                             var title=""
                                             if (k=="json") {
                                                 icon="icon-file"
                                                 title="JSON direct link"
                                             }
                                             if (k=="open") {
                                                 icon="icon-home"
                                                 title="Open URL"
                                             }
                                             if (icon!="") text=""
                                             actionsText = actionsText +
                                                "<a href='"+Util.prep(v)+"'"+
                                                " class='"+Util.prep(icon)+"'"+
                                             	" title='"+Util.prep(title)+"'>"+
                                                 Util.prep(text)+"</a>\n";
                                         })
                                         return actionsText;
                                     },
                                     "aTargets": [ 2 ]
                                 },
                                 { // value
                                     "mRender": function ( data, type, row ) {
                                         //var linked = (_.isString(data)) ? data.autoLink() : data;
                                         return Util.prep(Util.roundIfNumberToNumDecimalPlaces(data, 4))
                                     },
                                     "aTargets": [ 3 ]
                                 },
                                 // ID in column 0 is standard (assumed in ViewUtils)
                                 { "bVisible": false,  "aTargets": [ 0 ] }
                             ]            
            });
            ViewUtils.addFilterEmptyButton(that.table);
            ViewUtils.addAutoRefreshButton(that.table);
            ViewUtils.addRefreshButton(that.table);
            that.loadSensorMetadata(that);
            that.updateSensorsPeriodically(that);
            that.toggleFilterEmpty();
        },
        render:function () {
            this.updateSensorsNow(this);
            return this;
        },
        toggleFilterEmpty:function () {
            ViewUtils.toggleFilterEmpty(this.$('#sensors-table'), 3);
        },
        toggleAutoRefresh:function () {
            ViewUtils.toggleAutoRefresh(this);
        },
        enableAutoRefresh: function(isEnabled) {
            this.refreshActive = isEnabled
        },
        refreshSensors:function () {
            this.updateSensorsNow(this);  
        },
        updateSensorsPeriodically:function (that) {
            var self = this;
            that.callPeriodically("entity-sensors", function() {
                if (self.refreshActive)
                    self.updateSensorsNow(that);
            }, 3000);
        },
        loadSensorMetadata: function(that) {
            var url =  that.model.getLinkByName('sensors');
            $.get(url, function (data) {
                for (d in data) {
                    var sensor = data[d];
                    var actions = {};
                    _.each(sensor["links"], function(v,k) {
                        if (k.slice(0, 7) == "action:") {
                            actions[k.slice(7)] = v;
                        }
                    });
                    that.sensorMetadata[sensor["name"]] = {
                          name:sensor["name"],
                          description:sensor["description"],
                          actions:actions,
                          type:sensor["type"]
                    }
                }
                that.updateSensorsNow(that);
                that.table.find('*[rel="tooltip"]').tooltip();
            });
        },
        updateSensorsNow:function (that) {
            var url = that.model.getSensorUpdateUrl(),
                $table = that.$('#sensors-table');
            $.get(url, function (data) {
                ViewUtils.updateMyDataTable($table, data, function(value, name) {
                    var metadata = that.sensorMetadata[name]
                    if (metadata==null) {                        
                        // TODO should reload metadata when this happens (new sensor for which no metadata known)
                        // (currently if we have dynamic sensors, their metadata won't appear
                        // until the page is refreshed; don't think that's a bit problem -- mainly tooltips
                        // for now, we just return the partial value
                        return [name, {'name':name}, {}, value]
                    } 
                    return [name, metadata,
                        metadata["actions"],
                        value
                    ];
                });
            });
        }
    });
    return EntitySensorsView;
});
